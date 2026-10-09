package io.github.jdubois.bootui.quarkus.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuarkusRequestCorrelationTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aClearedScopeOverridesTheRequestsDuplicatedContext() throws Exception {
        CorrelationContext request = CorrelationContext.forRequest("r1");
        Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<String> cleared = new CompletableFuture<>();
        CompletableFuture<String> afterClearing = new CompletableFuture<>();

        duplicated.runOnContext(ignored -> {
            QuarkusRequestCorrelation.attach(request);
            try (BootUiCorrelation.Scope scope = BootUiCorrelation.openCleared()) {
                cleared.complete(
                        String.valueOf(QuarkusRequestCorrelation.current().requestId()));
            }
            afterClearing.complete(
                    String.valueOf(QuarkusRequestCorrelation.current().requestId()));
        });

        assertThat(cleared.get(10, TimeUnit.SECONDS))
                .as("clearing the scope must win over the request's duplicated context")
                .isEqualTo("null");
        assertThat(afterClearing.get(10, TimeUnit.SECONDS)).isEqualTo("r1");
    }

    @Test
    void theRequestLineFollowsTheRequestAndIsHiddenOffItsContext() throws Exception {
        Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<String> onEventLoop = new CompletableFuture<>();
        CompletableFuture<String> onWorker = new CompletableFuture<>();
        CompletableFuture<String> cleared = new CompletableFuture<>();

        duplicated.runOnContext(ignored -> {
            QuarkusRequestCorrelation.attach(CorrelationContext.forRequest("r1"), "GET", "/api/sample/boom");
            onEventLoop.complete(String.valueOf(QuarkusRequestCorrelation.currentRequestLine()));
            try (BootUiCorrelation.Scope scope = BootUiCorrelation.openCleared()) {
                cleared.complete(String.valueOf(QuarkusRequestCorrelation.currentRequestLine()));
            }
            duplicated
                    .executeBlocking(() -> String.valueOf(QuarkusRequestCorrelation.currentRequestLine()))
                    .onComplete(result -> onWorker.complete(result.result()));
        });

        String expected = new QuarkusRequestCorrelation.RequestLine("GET", "/api/sample/boom").toString();
        assertThat(onEventLoop.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(onWorker.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(cleared.get(10, TimeUnit.SECONDS)).isEqualTo("null");
        assertThat(QuarkusRequestCorrelation.attach(CorrelationContext.forRequest("r2"), "GET", "/x"))
                .isFalse();
        assertThat(QuarkusRequestCorrelation.currentRequestLine()).isNull();
    }

    @Test
    void offAVertxContextNothingIsAttachedAndTheThreadScopeDecides() {
        CorrelationContext request = CorrelationContext.forRequest("r1");

        assertThat(QuarkusRequestCorrelation.attach(request)).isFalse();
        assertThat(QuarkusRequestCorrelation.current()).isSameAs(CorrelationContext.NONE);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            assertThat(QuarkusRequestCorrelation.current()).isEqualTo(request);
        }
    }

    @Test
    void theContextFollowsTheRequestFromTheEventLoopToAWorkerThread() throws Exception {
        CorrelationContext request = CorrelationContext.forRequest("r1");
        Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<String> onWorker = new CompletableFuture<>();

        duplicated.runOnContext(ignored -> {
            QuarkusRequestCorrelation.attach(request);
            duplicated
                    .executeBlocking(() -> {
                        String thread = Thread.currentThread().getName();
                        return thread + "|"
                                + QuarkusRequestCorrelation.current().requestId();
                    })
                    .onComplete(result -> onWorker.complete(result.result()));
        });

        String[] observed = onWorker.get(10, TimeUnit.SECONDS).split("\\|");
        assertThat(observed[0]).contains("worker");
        assertThat(observed[1]).isEqualTo("r1");
    }

    @Test
    void anotherRequestsContextNeverSeesIt() throws Exception {
        Context first = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        Context second = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<CorrelationContext> seenBySecond = new CompletableFuture<>();

        first.runOnContext(ignored -> {
            QuarkusRequestCorrelation.attach(CorrelationContext.forRequest("r1"));
            second.runOnContext(alsoIgnored -> seenBySecond.complete(QuarkusRequestCorrelation.current()));
        });

        assertThat(seenBySecond.get(10, TimeUnit.SECONDS)).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void aReusedWorkerThreadCarriesNoContextIntoTheNextRequest() throws Exception {
        WorkerExecutor worker = vertx.createSharedWorkerExecutor("bootui-leak-probe", 1);
        try {
            Context first = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
            Context next = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
            CompletableFuture<String> firstRead = new CompletableFuture<>();
            CompletableFuture<String> nextRead = new CompletableFuture<>();

            first.runOnContext(ignored -> {
                QuarkusRequestCorrelation.attach(CorrelationContext.forRequest("r1"));
                worker.executeBlocking(this::describeWorker).onComplete(result -> firstRead.complete(result.result()));
            });
            String[] firstSeen = firstRead.get(10, TimeUnit.SECONDS).split("\\|");
            next.runOnContext(ignored -> worker.executeBlocking(this::describeWorker)
                    .onComplete(result -> nextRead.complete(result.result())));
            String[] nextSeen = nextRead.get(10, TimeUnit.SECONDS).split("\\|");

            assertThat(nextSeen[0]).as("the same worker thread").isEqualTo(firstSeen[0]);
            assertThat(firstSeen[1]).isEqualTo("r1");
            assertThat(nextSeen[1]).as("the next request's context").isEqualTo("null");
            assertThat(nextSeen[2]).as("the worker thread's own holder").isEqualTo("null");
        } finally {
            worker.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private String describeWorker() {
        return Thread.currentThread().getName() + "|"
                + QuarkusRequestCorrelation.current().requestId() + "|"
                + BootUiCorrelation.current().requestId();
    }

    @Test
    void aThreadScopeTakesPrecedenceOverTheRequestContext() throws Exception {
        Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<String> seen = new CompletableFuture<>();

        duplicated.runOnContext(ignored -> {
            QuarkusRequestCorrelation.attach(CorrelationContext.forRequest("r1"));
            try (BootUiCorrelation.Scope scope =
                    BootUiCorrelation.open(CorrelationContext.forRequest("r1").withTransactionId("tx1"))) {
                seen.complete(QuarkusRequestCorrelation.current().transactionId());
            }
        });

        assertThat(seen.get(10, TimeUnit.SECONDS)).isEqualTo("tx1");
    }
}
