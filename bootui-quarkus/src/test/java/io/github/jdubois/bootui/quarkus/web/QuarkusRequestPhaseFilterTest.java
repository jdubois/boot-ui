package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;
import org.junit.jupiter.api.Test;

/**
 * Pins that a Quarkus REST worker stops being metered for a request when Quarkus completes that request on it
 * ({@code docs/PLAN-v2.md} §5.11). The request's segment used to stay open until the event loop's
 * {@code bodyEndHandler} took the request, so whatever the worker did back in its pool — unrelated work, or a task an
 * agent propagated for another request — was charged to the request that had already finished, and its JFR samples
 * were joined to it.
 */
class QuarkusRequestPhaseFilterTest {

    @Test
    void aWorkerStopsBeingMeteredWhenQuarkusCompletesTheRequestOnIt() throws Exception {
        String requestId = RequestIds.next();
        SegmentMeter meter = SegmentMeter.shared();

        try {
            String meteredAfterChain = onWorkerOfRequest(requestId, request -> {
                QuarkusRequestPhaseFilter filter = new QuarkusRequestPhaseFilter(new RequestPhases());
                filter.filter(request.context());
                assertThat(meter.currentRequestId())
                        .as("the worker is metered for the request while it runs its chain")
                        .isEqualTo(requestId);

                filter.filter(request.context(), mock(ContainerResponseContext.class));
                assertThat(meter.currentRequestId())
                        .as("response filters and entity serialization are still this request's work")
                        .isEqualTo(requestId);

                request.complete(null);
                return meter.currentRequestId();
            });

            assertThat(meteredAfterChain)
                    .as("the worker goes back to its pool metered for nothing")
                    .isNull();
        } finally {
            meter.take(requestId);
        }
    }

    @Test
    void aWorkerStopsBeingMeteredWhenTheRequestCompletesWithAFailure() throws Exception {
        String requestId = RequestIds.next();
        SegmentMeter meter = SegmentMeter.shared();

        try {
            String meteredAfterChain = onWorkerOfRequest(requestId, request -> {
                new QuarkusRequestPhaseFilter(new RequestPhases()).filter(request.context());
                request.complete(new IllegalStateException("serialization failed"));
                return meter.currentRequestId();
            });

            assertThat(meteredAfterChain)
                    .as("a request that ends in an exception still releases its worker")
                    .isNull();
        } finally {
            meter.take(requestId);
        }
    }

    @Test
    void aRequestCompletedOnAnotherThreadIsStillClosedByTake() throws Exception {
        String requestId = RequestIds.next();
        SegmentMeter meter = SegmentMeter.shared();

        AtomicReference<CompletionCallback> completion = new AtomicReference<>();
        String meteredAfterChain = onWorkerOfRequest(requestId, request -> {
            new QuarkusRequestPhaseFilter(new RequestPhases()).filter(request.context());
            completion.set(request.callback());
            // The chain hands off and is completed elsewhere: this worker never hears the end of it.
            return meter.currentRequestId();
        });

        assertThat(meteredAfterChain)
                .as("the hand-off leaves the worker's segment open")
                .isEqualTo(requestId);
        String elsewhere = RequestIds.next();
        meter.begin(elsewhere); // this thread is busy with another request when the hand-off completes
        try {
            completion.get().onComplete(null);
            assertThat(meter.currentRequestId())
                    .as("completing on another thread must not close the segment that thread is using")
                    .isEqualTo(elsewhere);
        } finally {
            meter.switchTo(null);
            meter.take(elsewhere);
        }
        assertThat(meter.take(requestId))
                .as("taking is the fallback that closes it")
                .isNotNull();
    }

    /** A Quarkus REST request context that records the completion callback BootUI registers on it. */
    private record RequestUnderTest(ContainerRequestContext context, AtomicReference<CompletionCallback> registered) {

        static RequestUnderTest create() {
            AtomicReference<CompletionCallback> registered = new AtomicReference<>();
            ServerRequestContext server = mock(ServerRequestContext.class);
            doAnswer(invocation -> {
                        registered.set(invocation.getArgument(0));
                        return null;
                    })
                    .when(server)
                    .registerCompletionCallback(org.mockito.ArgumentMatchers.any());
            ResteasyReactiveContainerRequestContext context = mock(ResteasyReactiveContainerRequestContext.class);
            org.mockito.Mockito.when(context.getServerRequestContext()).thenReturn(server);
            return new RequestUnderTest(context, registered);
        }

        CompletionCallback callback() {
            CompletionCallback callback = registered.get();
            assertThat(callback).as("BootUI registers a completion callback").isNotNull();
            return callback;
        }

        void complete(Throwable failure) {
            callback().onComplete(failure);
        }
    }

    /**
     * Runs {@code chain} on a Vert.x worker thread carrying the duplicated context of {@code requestId}, exactly as
     * Quarkus hands a blocking resource method its request.
     */
    private static String onWorkerOfRequest(String requestId, Function<RequestUnderTest, String> chain)
            throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
            CompletableFuture<String> metered = new CompletableFuture<>();
            duplicated
                    .executeBlocking(
                            () -> {
                                QuarkusRequestCorrelation.attach(CorrelationContext.forRequest(requestId));
                                SegmentMeter.shared().begin(requestId);
                                SegmentMeter.shared().switchTo(null); // the event loop began it, not this worker
                                return chain.apply(RequestUnderTest.create());
                            },
                            false)
                    .onComplete(result -> {
                        if (result.succeeded()) {
                            metered.complete(result.result());
                        } else {
                            metered.completeExceptionally(result.cause());
                        }
                    });
            return metered.get(30, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }
}
