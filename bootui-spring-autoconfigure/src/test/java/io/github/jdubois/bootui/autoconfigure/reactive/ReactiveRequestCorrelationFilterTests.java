package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tests for {@link ReactiveRequestCorrelationFilter} and {@link BootUiCorrelationThreadLocalAccessor}: each
 * application request gets its own request id, current while the chain assembles and, with Reactor's automatic
 * context propagation, on every thread the request hops to, never leaking to the next request.
 */
class ReactiveRequestCorrelationFilterTests {

    private final ReactiveRequestCorrelationFilter filter =
            new ReactiveRequestCorrelationFilter(new BootUiProperties());

    @AfterEach
    void resetPropagation() {
        Hooks.disable();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void givesEachRequestAFreshIdCurrentWhileTheChainAssemblesAndOnTheExchange() {
        List<CorrelationContext> seen = new ArrayList<>();
        List<CorrelationContext> inReactorContext = new ArrayList<>();
        WebFilterChain chain = exchange -> {
            seen.add(BootUiCorrelation.current());
            return Mono.deferContextual(context -> {
                inReactorContext.add(context.get(ReactiveRequestCorrelationFilter.CONTEXT_KEY));
                return Mono.empty();
            });
        };

        MockServerWebExchange first = exchange("/api/orders");
        MockServerWebExchange second = exchange("/api/orders");
        filter.filter(first, chain).block(Duration.ofSeconds(5));
        filter.filter(second, chain).block(Duration.ofSeconds(5));

        assertThat(seen)
                .extracting(CorrelationContext::requestId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
        assertThat(inReactorContext).containsExactlyElementsOf(seen);
        assertThat(ReactiveRequestCorrelationFilter.correlation(first)).isEqualTo(seen.get(0));
        assertThat(ReactiveRequestCorrelationFilter.correlation(second)).isEqualTo(seen.get(1));
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void automaticContextPropagationCarriesEachRequestsIdAcrossSchedulerHops() {
        Hooks.enable();
        Map<String, String> blockingReads = new ConcurrentHashMap<>();
        WebFilterChain chain = exchange -> {
            String path = exchange.getRequest().getPath().value();
            return Mono.fromCallable(() -> BootUiCorrelation.current().requestId())
                    .subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.parallel())
                    .doOnNext(id -> blockingReads.put(path, id))
                    .then();
        };
        List<MockServerWebExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            exchanges.add(exchange("/api/orders/" + i));
        }

        Flux.fromIterable(exchanges)
                .flatMap(exchange -> filter.filter(exchange, chain))
                .blockLast(Duration.ofSeconds(10));

        assertThat(blockingReads).hasSize(20);
        for (MockServerWebExchange exchange : exchanges) {
            assertThat(blockingReads.get(exchange.getRequest().getPath().value()))
                    .isEqualTo(ReactiveRequestCorrelationFilter.correlation(exchange)
                            .requestId());
        }
        assertThat(blockingReads.values()).doesNotHaveDuplicates();
    }

    @Test
    void theHandlerDecoratorCoversErrorRenderingAndTheFilterReusesItsId() {
        List<CorrelationContext> inHandler = new ArrayList<>();
        List<CorrelationContext> inErrorHandling = new ArrayList<>();
        MockServerWebExchange exchange = exchange("/api/orders");
        HttpHandler decorated = filter.apply((request, response) -> filter.filter(exchange, ignored -> {
                    inHandler.add(BootUiCorrelation.current());
                    return Mono.error(new IllegalStateException("boom"));
                })
                .onErrorResume(error -> Mono.deferContextual(context -> {
                    inErrorHandling.add(context.get(ReactiveRequestCorrelationFilter.CONTEXT_KEY));
                    return Mono.empty();
                })));

        decorated.handle(exchange.getRequest(), exchange.getResponse()).block(Duration.ofSeconds(5));

        assertThat(inHandler)
                .singleElement()
                .extracting(CorrelationContext::requestId)
                .isNotNull();
        assertThat(inErrorHandling).containsExactlyElementsOf(inHandler);
        assertThat(ReactiveRequestCorrelationFilter.correlation(exchange)).isEqualTo(inHandler.get(0));
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void theHandlerDecoratorSkipsBootUiOwnRequests() {
        List<CorrelationContext> seen = new ArrayList<>();
        MockServerWebExchange exchange = exchange("/bootui/api/overview");
        HttpHandler decorated = filter.apply((request, response) -> Mono.deferContextual(context -> {
            seen.add(context.getOrDefault(ReactiveRequestCorrelationFilter.CONTEXT_KEY, CorrelationContext.NONE));
            return Mono.empty();
        }));

        decorated.handle(exchange.getRequest(), exchange.getResponse()).block(Duration.ofSeconds(5));

        assertThat(seen).containsExactly(CorrelationContext.NONE);
    }

    @Test
    void theAccessorReadsWritesAndClearsBootUisHolder() {
        BootUiCorrelationThreadLocalAccessor accessor = new BootUiCorrelationThreadLocalAccessor();
        CorrelationContext request = CorrelationContext.forRequest("0123456789abcdef");

        assertThat(accessor.key()).isEqualTo(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
        assertThat(accessor.getValue()).isNull();
        accessor.setValue(request);
        assertThat(BootUiCorrelation.current()).isEqualTo(request);
        assertThat(accessor.getValue()).isEqualTo(request);
        accessor.setValue();
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        accessor.restore(request);
        assertThat(BootUiCorrelation.current()).isEqualTo(request);
        accessor.restore();
        assertThat(accessor.getValue()).isNull();
    }

    @Test
    void skipsBootUiOwnRequests() {
        List<CorrelationContext> seen = new ArrayList<>();
        MockServerWebExchange exchange = exchange("/bootui/api/overview");

        filter.filter(exchange, ignored -> {
                    seen.add(BootUiCorrelation.current());
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(5));

        assertThat(seen).containsExactly(CorrelationContext.NONE);
        assertThat(ReactiveRequestCorrelationFilter.correlation(exchange)).isSameAs(CorrelationContext.NONE);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    /** Turns Reactor's automatic context propagation on and off around a test, with BootUI's accessor registered. */
    private static final class Hooks {

        static void enable() {
            BootUiCorrelationThreadLocalAccessor.register();
            reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
        }

        static void disable() {
            reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
        }
    }
}
