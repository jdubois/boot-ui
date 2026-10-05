package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.HandlerMapping;

class RequestCorrelationFilterTests {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recordsServingThreadWindowAndServerTraceForApplicationRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/products");
        MDC.put("traceId", "server-created-trace");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.snapshot()).hasSize(1);
        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(record.method()).isEqualTo("GET");
        assertThat(record.path()).isEqualTo("/api/sample/products");
        assertThat(record.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(record.endMillis()).isGreaterThanOrEqualTo(record.startMillis());
        assertThat(traceRegistry.match(record.method(), record.path(), record.startMillis(), record.endMillis()))
                .isEqualTo("server-created-trace");
    }

    /**
     * With the BootUI agent ({@code docs/PLAN-v2.md} §5.14, M5-4b), an async request is marked assembly only, and a
     * request whose {@code isAsyncStarted()} throws, as a recycled one does, still ends its code-paths fragment and
     * keeps the chain's own exception.
     */
    @Test
    void theCodePathsFragmentEndsAndTheChainsExceptionStandsWhenIsAsyncStartedThrows() {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        try (org.mockito.MockedStatic<io.github.jdubois.bootui.engine.javaagent.AgentCodePaths> codePaths =
                org.mockito.Mockito.mockStatic(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths.class)) {
            codePaths
                    .when(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::bound)
                    .thenReturn(true);
            MockHttpServletRequest recycled = new MockHttpServletRequest("GET", "/api/boom") {
                @Override
                public boolean isAsyncStarted() {
                    throw new IllegalStateException("recycled");
                }
            };
            assertThatThrownBy(() -> filter.doFilter(recycled, new MockHttpServletResponse(), (req, res) -> {
                        throw new ServletException("boom");
                    }))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("boom");
            codePaths.verify(io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::end);
            codePaths.verify(
                    () -> io.github.jdubois.bootui.engine.javaagent.AgentCodePaths.assemblyOnly(
                            org.mockito.ArgumentMatchers.any()),
                    org.mockito.Mockito.never());

            MockHttpServletRequest async = new MockHttpServletRequest("GET", "/api/later");
            async.setAsyncSupported(true);
            List<String> requestIds = new ArrayList<>();
            assertThatCode(() -> filter.doFilter(async, new MockHttpServletResponse(), (req, res) -> {
                        requestIds.add(BootUiCorrelation.current().requestId());
                        req.startAsync();
                    }))
                    .doesNotThrowAnyException();
            codePaths.verify(
                    () -> io.github.jdubois.bootui.engine.javaagent.AgentCodePaths.assemblyOnly(requestIds.get(0)));
            codePaths.verify(
                    io.github.jdubois.bootui.engine.javaagent.AgentCodePaths::end, org.mockito.Mockito.times(2));
        }
    }

    @Test
    void publishesOneHttpEventPerRequestWithItsRouteOperationStatusAndRequestId() throws Exception {
        RequestPhases phases = new RequestPhases();
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui", null, 1_000, phases);
        List<RuntimeEvent> published = new ArrayList<>();
        filter.setRuntimeEventSink(published::add);
        List<String> requestIds = new ArrayList<>();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/graphql");
        FilterChain chain = (req, res) -> {
            String requestId = BootUiCorrelation.current().requestId();
            requestIds.add(requestId);
            phases.setOperation(requestId, "query ProductList");
            phases.mark(requestId, RequestPhase.HANDLER);
            phases.mark(requestId, RequestPhase.RESPONSE);
            ((MockHttpServletResponse) res).setStatus(201);
        };
        long before = System.nanoTime();

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/boom"), new MockHttpServletResponse(), (req, res) -> {
                            throw new ServletException("boom");
                        }))
                .isInstanceOf(ServletException.class);
        filter.doFilter(
                new MockHttpServletRequest("GET", "/bootui/api/overview"), new MockHttpServletResponse(), chain);

        assertThat(published).hasSize(2);
        RuntimeEvent graphql = published.get(0);
        assertThat(graphql.source()).isEqualTo(JournalSource.HTTP);
        assertThat(graphql.requestId()).isEqualTo(requestIds.get(0));
        assertThat(graphql.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(graphql.durationNanos()).isNotNegative();
        assertThat(graphql.failedOrSlow()).isFalse();
        assertThat(withoutResources(graphql.payload()))
                .isEqualTo(new HttpPayload("POST", "/graphql", "/graphql", "query ProductList", 201));
        assertThat(published.get(1).failedOrSlow()).isTrue();
        assertThat(withoutResources(published.get(1).payload()))
                .isEqualTo(new HttpPayload("GET", "/api/boom", null, null, 500));
        RequestTiming timing = ((HttpPayload) graphql.payload()).timing();
        assertThat(timing.startNanos()).isGreaterThanOrEqualTo(before);
        assertThat(timing.phased()).isTrue();
        assertThat(timing.responseOffsetNanos()).isGreaterThanOrEqualTo(timing.handlerOffsetNanos());
        assertThat(timing.authenticationNanos()).isZero();
        assertThat(((HttpPayload) published.get(1).payload()).timing().phased())
                .as("a request whose handler never ran has no phases")
                .isFalse();
    }

    @Test
    void marksTheEndOfARequestAndOfOneThatAnswersOnALaterAsyncDispatchOnlyOnceItsAsyncContextCompletes()
            throws Exception {
        RequestPhases phases = new RequestPhases();
        List<String> ended = new ArrayList<>();
        phases.addEndListener(ended::add);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui", null, 1_000, phases);
        List<String> requestIds = new ArrayList<>();
        FilterChain chain =
                (req, res) -> requestIds.add(BootUiCorrelation.current().requestId());

        filter.doFilter(new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), chain);
        MockHttpServletRequest async = new MockHttpServletRequest("GET", "/api/orders/later");
        async.setAsyncSupported(true);
        filter.doFilter(async, new MockHttpServletResponse(), (req, res) -> {
            requestIds.add(BootUiCorrelation.current().requestId());
            req.startAsync();
        });

        assertThat(phases.markers(requestIds.get(0)).endedAt()).isNotNull();
        assertThat(phases.markers(requestIds.get(1)).endedAt())
                .as("its handler is still running")
                .isNull();
        assertThat(ended).containsExactly(requestIds.get(0));

        async.getAsyncContext().complete();

        assertThat(phases.markers(requestIds.get(1)).endedAt())
                .as("its async context completed: its response was written")
                .isNotNull();
        assertThat(ended).containsExactly(requestIds.get(0), requestIds.get(1));
    }

    /** The payload without its measured resources, which every published request carries (docs/PLAN-v2.md §5.11). */
    private static HttpPayload withoutResources(Object payload) {
        HttpPayload http = (HttpPayload) payload;
        assertThat(http.resources()).as("the request's measured resources").isNotNull();
        assertThat(http.resources().segments()).isPositive();
        return new HttpPayload(http.method(), http.path(), http.routeTemplate(), http.operation(), http.status());
    }

    @Test
    void makesAFreshRequestIdCurrentWhileTheChainRunsAndRestoresTheThreadAfter() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        List<CorrelationContext> seen = new ArrayList<>();
        FilterChain chain = (request, response) -> seen.add(BootUiCorrelation.current());

        filter.doFilter(new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), chain);

        assertThat(seen)
                .extracting(CorrelationContext::requestId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void restoresTheThreadWhenTheChainThrows() {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        FilterChain failing = (request, response) -> {
            throw new ServletException("handler failed");
        };

        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);

        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void anAsyncRedispatchRunsUnderTheSameRequestIdWithoutBeingRecordedAgain() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        RequestCorrelationFilter filter =
                new RequestCorrelationFilter(registry, new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        List<String> seen = new ArrayList<>();
        FilterChain chain = (req, res) -> seen.add(BootUiCorrelation.current().requestId());

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        request.setDispatcherType(DispatcherType.ASYNC);
        request.removeAttribute(filter.getClass().getName() + ".FILTERED");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).isEqualTo(seen.get(0)).isNotNull();
        assertThat(registry.snapshot()).hasSize(1);
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void anAsyncTimeoutAndItsErrorDispatchRunUnderTheRequestIdAndLeaveTheThreadClean() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        List<String> seen = new ArrayList<>();
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seen.add(BootUiCorrelation.current().requestId()));

        request.setDispatcherType(DispatcherType.ASYNC);
        request.removeAttribute(filter.getClass().getName() + ".FILTERED");
        FilterChain timesOut = (req, res) -> {
            seen.add(BootUiCorrelation.current().requestId());
            throw new AsyncRequestTimeoutException();
        };
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), timesOut))
                .isInstanceOf(AsyncRequestTimeoutException.class);
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);

        request.setDispatcherType(DispatcherType.ERROR);
        request.removeAttribute(filter.getClass().getName() + ".FILTERED");
        FilterChain errorPageFails = (req, res) -> {
            seen.add(BootUiCorrelation.current().requestId());
            throw new ServletException("error page failed");
        };
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), errorPageFails))
                .isInstanceOf(ServletException.class);

        assertThat(seen).hasSize(3).containsOnly(seen.get(0)).doesNotContainNull();
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void skipsBootUiOwnRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootui/api/activity/stream");
        List<CorrelationContext> seen = new ArrayList<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.add(BootUiCorrelation.current()));

        assertThat(seen)
                .as("BootUI's own work is marked, and carries no request id")
                .containsExactly(CorrelationContext.BOOTUI);
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        assertThat(registry.snapshot()).isEmpty();
        long now = System.currentTimeMillis();
        assertThat(traceRegistry.match("GET", "/bootui/api/activity/stream", now - 1000, now + 1000))
                .isNull();
    }

    @Test
    void capturesTheMatchedRouteTemplateSoSqlAttributionCanGroupWithoutPathValues() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        RequestCorrelationFilter filter =
                new RequestCorrelationFilter(registry, new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sample/orders/{id}");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(record.routeTemplate()).isEqualTo("/api/sample/orders/{id}");
        assertThat(record.path()).isEqualTo("/api/sample/orders/42");
    }

    @Test
    void leavesTheRouteTemplateNullWhenNoHandlerPatternMatched() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        RequestCorrelationFilter filter =
                new RequestCorrelationFilter(registry, new HttpExchangeTraceRegistry(10), "/bootui");

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/sample/unmapped"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(registry.snapshot().get(0).routeTemplate()).isNull();
    }

    @Test
    void normalizesEncodedPathLikeActuatorBeforeRegisteringServerTrace() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/a%20b");
        MDC.put("traceId", "server-created-trace");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(traceRegistry.match("GET", "/api/items/a b", record.startMillis(), record.endMillis()))
                .isEqualTo("server-created-trace");
    }

    @Test
    void reservesTraceRecordsOfFailedThrowingAndSlowRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(4, 75);
        RequestCorrelationFilter filter =
                new RequestCorrelationFilter(registry, traceRegistry, "/bootui", "/bootui/api", 1L);
        MockHttpServletResponse serverError = new MockHttpServletResponse();
        serverError.setStatus(503);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/failing"), serverError, new MockFilterChain());
        try {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/throwing"),
                    new MockHttpServletResponse(),
                    (request, response) -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (IllegalStateException expected) {
            // The filter must never swallow the application's exception.
        }
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/slow"), new MockHttpServletResponse(), (request, response) -> {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        RequestCorrelationFilter routineFilter =
                new RequestCorrelationFilter(registry, traceRegistry, "/bootui", "/bootui/api", 0L);
        for (int i = 0; i < 10; i++) {
            routineFilter.doFilter(
                    new MockHttpServletRequest("GET", "/api/ok-" + i),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        assertThat(traceRegistry.recent())
                .extracting(HttpExchangeTraceRegistry.HttpExchangeTrace::path)
                .containsExactly("/api/failing", "/api/throwing", "/api/slow", "/api/ok-9");
    }

    @Test
    void classifiesAnyEscapingExceptionAsAServerErrorAsActuatorRecordsIt() throws Exception {
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(2, 50);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), traceRegistry, "/bootui", "/bootui/api", 0L);
        try {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/missing"),
                    new MockHttpServletResponse(),
                    (request, response) -> {
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND);
                    });
        } catch (org.springframework.web.server.ResponseStatusException expected) {
            // Actuator's servlet HttpExchangesFilter records this exchange as a 500.
        }
        for (int i = 0; i < 5; i++) {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/ok-" + i),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        assertThat(traceRegistry.recent())
                .extracting(HttpExchangeTraceRegistry.HttpExchangeTrace::path)
                .containsExactly("/api/missing", "/api/ok-4");
    }

    @Test
    void skipsBootUiRequestsBelowTheContextPathAndOnASeparateApiMount() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter =
                new RequestCorrelationFilter(registry, traceRegistry, "/console", "/internal/console-api", 1_000L);

        for (String path : List.of("/console/index.html", "/internal/console-api/activity", "/api/orders")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/host" + path);
            request.setContextPath("/host");
            request.setQueryString("next=/console");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        assertThat(registry.snapshot()).extracting(RequestCorrelation::path).containsExactly("/host/api/orders");
        assertThat(traceRegistry.recent())
                .extracting(HttpExchangeTraceRegistry.HttpExchangeTrace::path)
                .containsExactly("/host/api/orders");
    }
}
