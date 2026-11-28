package io.github.jdubois.bootui.quarkus4.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import io.github.jdubois.bootui.engine.javaagent.AgentEventLoops;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.quarkus.runtime.ImageMode;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code docs/PLAN-v2.md} §5.1: Quarkus classifies Vert.x's own threads from Vert.x, never from their names. */
class QuarkusThreadKindsTests {

    @Test
    void admittedNativeClassificationDoesNotRegisterJvmAgentEventLoops() {
        try (var activation = mockStatic(BootUiNativeActivation.class);
                var image = mockStatic(ImageMode.class);
                var context = mockStatic(Context.class);
                var agent = mockStatic(AgentEventLoops.class)) {
            image.when(ImageMode::current).thenReturn(ImageMode.NATIVE_RUN);
            context.when(Context::isOnEventLoopThread).thenReturn(true);
            assertThat(kinds.current()).isEqualTo(ThreadKind.EVENT_LOOP);
            QuarkusThreadKinds.registerIfEventLoop();
            agent.verifyNoInteractions();
        }
    }

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
