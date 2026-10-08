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
        MockServerWebExchange missing = exchange("/api/missing");
        assertThatThrownBy(() -> filter.filter(missing, fails).block(Duration.ofSeconds(5)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(published)
                .as("a failure is published once WebFlux's exception handlers rendered it")
                .hasSize(1);
        missing.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        missing.getResponse().setComplete().block(Duration.ofSeconds(5));

        assertThat(published).hasSize(2);
        assertThat(published.get(0).source()).isEqualTo(JournalSource.HTTP);
        assertThat(published.get(0).requestId())
                .isEqualTo(ReactiveRequestCorrelationFilter.correlation(ok).requestId());
        assertThat(withoutResources(published.get(0).payload()))
                .isEqualTo(new HttpPayload("GET", "/api/orders/42", "/api/orders/{id}", null, 200));
        assertThat(withoutResources(published.get(1).payload()))
                .isEqualTo(new HttpPayload("GET", "/api/missing", null, null, 404));
        assertThat(published.get(1).failedOrSlow()).isFalse();
        assertThat(((HttpPayload) published.get(0).payload()).timing())
                .as("WebFlux marks no phases, so only the monotonic start is known")
                .satisfies(timing -> {
                    assertThat(timing.startNanos()).isPositive();
                    assertThat(timing.phased()).isFalse();
                });
    }

    /** The payload without its measured resources, which every published request carries (docs/PLAN-v2.md §5.11). */
    private static HttpPayload withoutResources(Object payload) {
        HttpPayload http = (HttpPayload) payload;
        assertThat(http.resources()).as("the request's measured resources").isNotNull();
        assertThat(http.resources().segments()).isPositive();
        return new HttpPayload(http.method(), http.path(), http.routeTemplate(), http.operation(), http.status());
    }

    /**
     * With the BootUI agent ({@code docs/PLAN-v2.md} §5.14, M5-4b), the code-paths fragment opens inside the request's
     * scope, so it captures the request, and closes once the synchronous subscription returned; the subscription sees
     * the thread's correlation exactly as without the agent, and no request is marked assembly only per request: the
     * reactive configuration marks every WebFlux tree once.
     */
    @Test
    void withTheAgentTheFragmentSpansTheSubscriptionWithoutChangingTheThreadsCorrelation() {
        List<String> calls = new ArrayList<>();
        List<CorrelationContext> inSubscription = new ArrayList<>();
        List<CorrelationContext> withoutAgent = new ArrayList<>();
        HttpHandler handler = (request, response) -> Mono.defer(() -> {
            inSubscription.add(BootUiCorrelation.current());
            calls.add("subscribe");
            return Mono.empty();
        });
        MockServerWebExchange plain = exchange("/api/orders");
        HttpHandler decorated = filter.apply(handler);
        decorated.handle(plain.getRequest(), plain.getResponse()).block(Duration.ofSeconds(5));
        withoutAgent.addAll(inSubscription);
        inSubscription.clear();
        calls.clear();

        try (org.mockito.MockedStatic<io.github.jdubois.bootui.engine.javaagent.AgentCodePaths> codePaths =
                org.mockito.Mockito.mockStatic(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths.class)) {
            List<CorrelationContext> atBegin = new ArrayList<>();
            codePaths
                    .when(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::bound)
                    .thenReturn(true);
            codePaths
                    .when(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::begin)
                    .then(invocation -> {
                        atBegin.add(BootUiCorrelation.current());
                        calls.add("begin");
                        return null;
                    });
            codePaths
                    .when(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::end)
                    .then(invocation -> {
                        calls.add("end");
                        return null;
                    });
            MockServerWebExchange exchange = exchange("/api/orders");
            filter.apply(handler)
                    .handle(exchange.getRequest(), exchange.getResponse())
                    .block(Duration.ofSeconds(5));

            assertThat(calls).containsExactly("begin", "subscribe", "end");
            assertThat(atBegin)
                    .singleElement()
                    .extracting(CorrelationContext::requestId)
                    .asString()
                    .matches("[0-9a-f]{16}");
            assertThat(inSubscription)
                    .as("the subscription sees what it sees without the agent")
                    .containsExactlyElementsOf(withoutAgent);
            codePaths.verify(
                    () -> io.github.jdubois.bootui.engine.javaagent.AgentCodePaths.assemblyOnly(
                            org.mockito.ArgumentMatchers.any()),
                    org.mockito.Mockito.never());
        }
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    /**
     * A failure is published with the status the application's own exception handler rendered, not one guessed from the
     * failure, on WebFlux's real pipeline: a custom handler's {@code 400}, a successful fallback, an unhandled failure's
     * {@code 500}, and a {@code ResponseStatusException}'s own status. The exchange trace record is kept then too.
     */
    @Test
    void aFailureIsPublishedWithTheStatusTheApplicationsExceptionHandlerRendered() {
        List<RuntimeEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        filter.setRuntimeEventSink(published::add, 1_000, null);
        io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry traces =
                new io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter traceFilter =
                new ReactiveHttpExchangeTraceFilter(new BootUiProperties(), traces, () -> null, 1_000);
        org.springframework.web.server.WebHandler handler =
                exchange -> switch (exchange.getRequest().getPath().value()) {
                    case "/api/invalid" -> Mono.error(new IllegalArgumentException("invalid order"));
                    case "/api/fallback" -> Mono.error(new UnsupportedOperationException("served from cache"));
                    case "/api/gone" -> Mono.error(new ResponseStatusException(HttpStatus.GONE));
                    default -> Mono.error(new IllegalStateException("unhandled"));
                };
        org.springframework.web.server.WebExceptionHandler custom = (exchange, failure) -> {
            if (failure instanceof IllegalArgumentException) {
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }
            if (failure instanceof UnsupportedOperationException) {
                return exchange.getResponse()
                        .writeWith(
                                Mono.just(exchange.getResponse().bufferFactory().wrap(new byte[] {'o', 'k'})));
            }
            return Mono.error(failure);
        };
        HttpHandler pipeline = org.springframework.web.server.adapter.WebHttpHandlerBuilder.webHandler(handler)
                .filters(filters -> {
                    filters.add(filter);
                    filters.add(traceFilter);
                })
                .exceptionHandlers(handlers -> {
                    handlers.add(custom);
                    handlers.add(new org.springframework.web.server.handler.ResponseStatusExceptionHandler());
                })
                .build();

        Map<String, Integer> rendered = new java.util.LinkedHashMap<>();
        for (String path : List.of("/api/invalid", "/api/fallback", "/api/gone", "/api/unhandled")) {
            org.springframework.mock.http.server.reactive.MockServerHttpResponse response =
                    new org.springframework.mock.http.server.reactive.MockServerHttpResponse();
            pipeline.handle(MockServerHttpRequest.get(path).build(), response).block(Duration.ofSeconds(5));
            rendered.put(
                    path,
                    response.getStatusCode() == null
                            ? 200
                            : response.getStatusCode().value());
        }

        assertThat(rendered)
                .containsExactly(
                        Map.entry("/api/invalid", 400),
                        Map.entry("/api/fallback", 200),
                        Map.entry("/api/gone", 410),
                        Map.entry("/api/unhandled", 500));
        assertThat(published).hasSize(4);
        for (RuntimeEvent event : published) {
            HttpPayload http = (HttpPayload) event.payload();
            assertThat(http.status()).as(http.path()).isEqualTo(rendered.get(http.path()));
            assertThat(event.failedOrSlow()).as(http.path()).isEqualTo(http.status() >= 500);
        }
        io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.Matcher matcher = traces.matcher();
        assertThat(published)
                .as("each request's trace record is kept once its outcome is known")
                .allSatisfy(event ->
                        assertThat(matcher.byRequestId(event.requestId())).isNotNull());
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
    void theHandlerDecoratorMarksBootUiOwnRequestsSoTheirWorkStaysOutOfTheJournal() {
        List<CorrelationContext> seen = new ArrayList<>();
        MockServerWebExchange exchange = exchange("/bootui/api/overview");
        HttpHandler decorated = filter.apply((request, response) -> Mono.deferContextual(context -> {
            seen.add(context.getOrDefault(ReactiveRequestCorrelationFilter.CONTEXT_KEY, CorrelationContext.NONE));
            return Mono.empty();
        }));

        decorated.handle(exchange.getRequest(), exchange.getResponse()).block(Duration.ofSeconds(5));

        assertThat(seen).containsExactly(CorrelationContext.BOOTUI);
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
