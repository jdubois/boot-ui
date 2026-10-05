package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.RequestValuesBinding;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Spring MVC's push points of the request value holder ({@code docs/PLAN-v2.md} §5.16, M5-6b): query values from
 * BootUI's own decoding of the query string and the handler mapping's path variables, never through a
 * {@code getParameter*} call, so a body stays unread; nothing on another dispatch or while matching is off; and the
 * values removed where the response really completes.
 */
class RequestValuesMvcTests {

    private static final String REQUEST = "00000000000000ab";

    @BeforeEach
    void bind() {
        FakeRequestValues.reset();
        RequestValuesBinding.bind(FakeRequestValues.class);
        AgentRequestValues.configure(true);
    }

    @AfterEach
    void unbind() {
        AgentRequestValues.configure(false);
        RequestValuesBinding.rebind();
        FakeRequestValues.reset();
    }

    @Test
    void queryAndPathValuesArePushedWithoutReadingAParameterOrTheBody() throws Exception {
        NoParameters request = new NoParameters("POST", "/api/reports/q3-report");
        request.setQueryString("name=Robert%27%29%3B+DROP&size=10");
        request.setContent("raw=body-content".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/x-www-form-urlencoded");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("file", "q3-report"));

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            RequestPhaseInterceptor.pushRequestValues(request);
        }

        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + REQUEST);
        assertThat(FakeRequestValues.PUSHED)
                .containsEntry("file", "q3-report")
                .containsEntry("name", "Robert'); DROP")
                .containsEntry("size", "10")
                .doesNotContainKey("raw");
        assertThat(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .as("the handler still reads its raw body")
                .isEqualTo("raw=body-content");
    }

    @Test
    void nothingIsPushedOnAnotherDispatchForBootUiOrWhileMatchingIsOff() {
        NoParameters request = new NoParameters("GET", "/api/search");
        request.setQueryString("name=alice");

        request.setDispatcherType(DispatcherType.ERROR);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            RequestPhaseInterceptor.pushRequestValues(request);
        }
        request.setDispatcherType(DispatcherType.REQUEST);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            RequestPhaseInterceptor.pushRequestValues(request);
        }
        FakeRequestValues.active = false;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            RequestPhaseInterceptor.pushRequestValues(request);
        }
        FakeRequestValues.active = true;
        AgentRequestValues.configure(false);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(REQUEST))) {
            RequestPhaseInterceptor.pushRequestValues(request);
        }

        assertThat(FakeRequestValues.CALLS).isEmpty();
    }

    @Test
    void aSynchronousRequestsValuesEndWithItsFilterChain() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search");
        AtomicReference<String> requestId = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            requestId.set(BootUiCorrelation.current().requestId());
            RequestPhaseInterceptor.pushRequestValues(withQuery((MockHttpServletRequest) req));
        });

        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + requestId.get(), "end " + requestId.get());
    }

    @Test
    void aChainThatThrowsStillEndsItsValues() {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search");
        AtomicReference<String> requestId = new AtomicReference<>();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                            requestId.set(BootUiCorrelation.current().requestId());
                            RequestPhaseInterceptor.pushRequestValues(withQuery((MockHttpServletRequest) req));
                            throw new IllegalStateException("boom");
                        }))
                .hasMessageContaining("boom");

        assertThat(FakeRequestValues.CALLS).containsExactly("begin " + requestId.get(), "end " + requestId.get());
    }

    @Test
    void aRequestWhoseValuesWereNeverPushedEndsNothing() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/search"), new MockHttpServletResponse(), (req, res) -> {});

        assertThat(FakeRequestValues.CALLS).isEmpty();
    }

    private static MockHttpServletRequest withQuery(MockHttpServletRequest request) {
        request.setQueryString("name=alice");
        return request;
    }

    @Test
    void anAsyncRequestsValuesEndWhenItsAsyncCycleCompletes() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(
                new RequestCorrelationRegistry(10), new HttpExchangeTraceRegistry(10), "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/slow");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestId = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            requestId.set(BootUiCorrelation.current().requestId());
            RequestPhaseInterceptor.pushRequestValues(withQuery((MockHttpServletRequest) req));
            req.startAsync();
        });

        assertThat(FakeRequestValues.CALLS).as("still running").containsExactly("begin " + requestId.get());
        MockAsyncContext async = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
            listener.onError(new AsyncEvent(async));
            listener.onComplete(new AsyncEvent(async));
        }
        assertThat(FakeRequestValues.CALLS)
                .as("ended on timeout, error, and completion alike; ending twice is harmless")
                .containsExactly(
                        "begin " + requestId.get(),
                        "end " + requestId.get(),
                        "end " + requestId.get(),
                        "end " + requestId.get());
    }

    /** A request whose parameters must never be read by BootUI. */
    static final class NoParameters extends MockHttpServletRequest {

        NoParameters(String method, String uri) {
            super(method, uri);
        }

        @Override
        public String getParameter(String name) {
            throw new AssertionError("getParameter");
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw new AssertionError("getParameterMap");
        }

        @Override
        public java.util.Enumeration<String> getParameterNames() {
            throw new AssertionError("getParameterNames");
        }

        @Override
        public String[] getParameterValues(String name) {
            throw new AssertionError("getParameterValues");
        }
    }
}
