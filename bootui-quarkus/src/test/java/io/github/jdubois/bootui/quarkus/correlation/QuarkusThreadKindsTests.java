package io.github.jdubois.bootui.quarkus.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.ThreadKind;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code docs/PLAN-v2.md} §5.1: Quarkus classifies Vert.x's own threads from Vert.x, never from their names. */
class QuarkusThreadKindsTests {

    private final Vertx vertx = Vertx.vertx();
    private final QuarkusThreadKinds kinds = new QuarkusThreadKinds();

    @AfterEach
    void close() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void classifiesTheEventLoopAWorkerAndAnyOtherThread() throws Exception {
        CompletableFuture<ThreadKind> onEventLoop = new CompletableFuture<>();
        CompletableFuture<ThreadKind> onWorker = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            onEventLoop.complete(kinds.current());
            vertx.executeBlocking(kinds::current).onComplete(result -> onWorker.complete(result.result()));
        });

        assertThat(onEventLoop.get(10, TimeUnit.SECONDS)).isEqualTo(ThreadKind.EVENT_LOOP);
        assertThat(onWorker.get(10, TimeUnit.SECONDS)).isEqualTo(ThreadKind.WORKER);
        assertThat(kinds.current()).isEqualTo(ThreadKind.OTHER);
    }
}
