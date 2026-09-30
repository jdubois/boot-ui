package io.github.jdubois.bootui.quarkus.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
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
