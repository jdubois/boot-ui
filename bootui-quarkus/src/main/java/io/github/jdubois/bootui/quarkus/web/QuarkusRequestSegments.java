package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.vertx.core.Context;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

/**
 * Opens and closes the {@link SegmentMeter} segment of a Quarkus REST request on the worker or virtual thread that
 * runs its JAX-RS chain ({@code docs/PLAN-v2.md} §5.11).
 *
 * <p>Quarkus keeps a request's correlation on its Vert.x duplicated context rather than in a thread-bound
 * {@link BootUiCorrelation} scope, so no scope opens or closes a segment when the request hops from the event loop to
 * a worker. {@link #enter(ContainerRequestContext)} opens it where the chain reaches the resource method, and closes
 * it on that same thread when Quarkus completes the request there, so the thread goes back to its pool metered for
 * nothing.</p>
 *
 * <p>For an ordinary response this close is not the one that matters, and the measurement was already right without
 * it: the worker itself writes the response, {@code Http1xServerResponse.end} runs the {@code bodyEndHandler} inline
 * on the thread that ended the response, and {@code SegmentMeter.take} therefore closes the worker's segment on the
 * worker, before the chain even reaches completion. The close here is what covers the chain that finishes on its
 * worker while the response body is still outstanding — a {@code File} or {@code Path} entity, which Quarkus writes
 * with {@code HttpServerResponse.sendFile} and whose body end is emitted later, on the event loop. Without it, such a
 * worker stays charged to the finished request for the whole transfer, and any work it does back in its pool in
 * between — including an agent-propagated task, which deliberately leaves the meter unchanged ({@code docs/PLAN-v2.md}
 * D32) — is measured under that request and has its JFR samples joined to it.</p>
 *
 * <p>The close rides on Quarkus' own request-completion callback rather than on a response filter or a writer
 * interceptor. Completion runs once the whole chain is done — after every response filter, after the entity has been
 * serialized, and after an exception mapper has written its own response — so nothing the request still owes is left
 * unmetered, and BootUI adds no provider that could change how Quarkus serializes an application's responses.</p>
 *
 * <p>A chain that leaves its worker without completing on it is left alone here and falls back to
 * {@code SegmentMeter.take} when the request's body ends. That fallback is the honest limit of this close: a
 * <em>suspended</em> chain — a blocking method returning a {@code Uni} or a {@code CompletionStage}, a {@code Multi},
 * an SSE or {@code @Suspended} response — releases its worker at suspension, which Quarkus 3.33 exposes no supported
 * hook for, so that worker stays attributed to the request until it is taken or until the worker switches to its next
 * measured request. {@code docs/PLAN-v2.md} §5.11 records that limit.</p>
 */
final class QuarkusRequestSegments {

    private QuarkusRequestSegments() {}

    /**
     * Meters this thread for the current request, where no correlation scope does it, and arranges to stop metering it
     * on this same thread when Quarkus completes the request.
     */
    static void enter(ContainerRequestContext request) {
        try {
            if (!BootUiCorrelation.current().isEmpty() || Context.isOnEventLoopThread()) {
                return;
            }
            String requestId = QuarkusRequestCorrelation.current().requestId();
            if (requestId == null) {
                return;
            }
            SegmentMeter.shared().switchTo(requestId);
            onCompletion(request, requestId, Thread.currentThread());
        } catch (RuntimeException | LinkageError ex) {
            // Measuring never disturbs the request.
        }
    }

    /**
     * Stops metering {@code owner} for {@code requestId} once Quarkus completes the request on that very thread. A
     * completion reached from anywhere else cannot close another thread's segment, so it defers to the fallback, and
     * so does a completion that arrives after the response body already ended and the request was taken.
     */
    private static void onCompletion(ContainerRequestContext request, String requestId, Thread owner) {
        if (!(request instanceof ResteasyReactiveContainerRequestContext resteasy)) {
            return;
        }
        resteasy.getServerRequestContext().registerCompletionCallback(throwable -> {
            try {
                if (Thread.currentThread() != owner) {
                    return;
                }
                SegmentMeter meter = SegmentMeter.shared();
                if (requestId.equals(meter.currentRequestId())) {
                    meter.switchTo(null);
                }
            } catch (RuntimeException | LinkageError ex) {
                // Measuring never disturbs the request.
            }
        });
    }
}
