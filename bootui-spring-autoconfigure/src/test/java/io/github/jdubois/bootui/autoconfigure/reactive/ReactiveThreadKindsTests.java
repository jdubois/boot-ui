package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.activity.ServletThreadKinds;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.netty.channel.EventLoopGroup;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.LoopResources;

/**
 * {@code docs/PLAN-v2.md} §5.1: the Spring classifiers read thread types, never names, on the real threads WebFlux and
 * Spring MVC run work on.
 */
class ReactiveThreadKindsTests {

    private final ReactiveThreadKinds reactive = new ReactiveThreadKinds();

    @Test
    void aReactorNettyEventLoopIsAnEventLoop() throws Exception {
        LoopResources loops = LoopResources.create("bootui-kinds", 1, true);
        try {
            EventLoopGroup group = loops.onServer(false);
            CompletableFuture<ThreadKind> kind = new CompletableFuture<>();
            group.next().execute(() -> kind.complete(reactive.current()));
            assertThat(kind.get(10, TimeUnit.SECONDS)).isEqualTo(ThreadKind.EVENT_LOOP);
        } finally {
            loops.disposeLater().block(Duration.ofSeconds(10));
        }
    }

    @Test
    void parallelIsAReactorSchedulerThatMustNotBlock() {
        ThreadKind kind = Mono.fromCallable(reactive::current)
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(10));

        assertThat(kind).isEqualTo(ThreadKind.REACTOR_SCHEDULER);
    }

    @Test
    void boundedElasticRunningARequestIsAWorker() {
        ThreadKind kind = Mono.fromCallable(() -> {
                    try (BootUiCorrelation.Scope ignored =
                            BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
                        return reactive.current();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(10));

        assertThat(kind).isEqualTo(ThreadKind.WORKER);
        assertThat(reactive.current()).as("an unowned thread").isEqualTo(ThreadKind.OTHER);
    }

    @Test
    void aServletThreadServingARequestIsAWorker() {
        ServletThreadKinds servlet = new ServletThreadKinds();
        assertThat(servlet.current()).isEqualTo(ThreadKind.OTHER);
        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            assertThat(servlet.current()).isEqualTo(ThreadKind.WORKER);
        }
    }
}
