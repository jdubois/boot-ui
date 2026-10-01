package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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
    void aCancelledTimedOutOrFailedRequestLeavesNoContextOnTheSchedulerThreadItUsed() {
        Hooks.enable();
        Scheduler single = Schedulers.newSingle("bootui-leak-probe");
        try {
            WebFilterChain hangs =
                    exchange -> Mono.delay(Duration.ofSeconds(30), single).then();
            WebFilterChain fails = exchange -> Mono.fromCallable(() -> "work")
                    .subscribeOn(single)
                    .flatMap(work -> Mono.<Void>error(new IllegalStateException("handler failed")));

            filter.filter(exchange("/api/cancelled"), hangs).subscribe().dispose();
            assertThatThrownBy(() -> filter.filter(exchange("/api/timed-out"), hangs)
                            .timeout(Duration.ofMillis(50))
                            .block(Duration.ofSeconds(5)))
                    .hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(
                            () -> filter.filter(exchange("/api/failed"), fails).block(Duration.ofSeconds(5)))
                    .hasMessageContaining("handler failed");

            // Read the thread's own holder, without automatic propagation restoring anything around the task.
            Hooks.disable();
            CorrelationContext left = Mono.fromCallable(BootUiCorrelation::current)
                    .subscribeOn(single)
                    .block(Duration.ofSeconds(5));
            assertThat(left).isSameAs(CorrelationContext.NONE);
            assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        } finally {
            single.dispose();
        }
    }

    @Test
    void publishesOneHttpEventPerRequestWithItsMatchedRouteAndRenderedStatus() {
        List<RuntimeEvent> published = new ArrayList<>();
        filter.setRuntimeEventSink(published::add, 1_000, null);
        MockServerWebExchange ok = exchange("/api/orders/42");
        WebFilterChain matches = exchange -> {
            exchange.getAttributes()
                    .put(
                            org.springframework.web.reactive.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                            "/api/orders/{id}");
            return Mono.empty();
        };
        WebFilterChain fails = exchange -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));

        filter.filter(ok, matches).block(Duration.ofSeconds(5));
        assertThatThrownBy(() -> filter.filter(exchange("/api/missing"), fails).block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(published).hasSize(2);
        assertThat(published.get(0).source()).isEqualTo(JournalSource.HTTP);
        assertThat(published.get(0).requestId())
                .isEqualTo(ReactiveRequestCorrelationFilter.correlation(ok).requestId());
        assertThat(published.get(0).payload())
                .isEqualTo(new HttpPayload("GET", "/api/orders/42", "/api/orders/{id}", null, 200));
        assertThat(published.get(1).payload()).isEqualTo(new HttpPayload("GET", "/api/missing", null, null, 404));
        assertThat(published.get(1).failedOrSlow()).isFalse();
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
