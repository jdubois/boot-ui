package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * White-box binding tests for {@link QuarkusHttpExchangeCaptureFilter}'s self-traffic exclusion: BootUI's
 * own requests must never reach the shared {@code HttpExchangeBuffer} (and therefore never reach the HTTP
 * Exchanges panel or Live Activity correlation), under the default root path, a non-default
 * {@code quarkus.http.root-path}, and a custom {@code bootui.path} mount alike — while application traffic
 * stays captured.
 */
class QuarkusHttpExchangeCaptureFilterTest {

    @Test
    void capturesApplicationTraffic() {
        RoutingContext rc = mockRequest("/orders/42");
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        completeRequest(filter(buffer, Map.of()), rc);

        verify(rc).next();
        assertThat(buffer.snapshot()).hasSize(1);
    }

    @Test
    void skipsBootUiTrafficUnderTheDefaultRootPath() {
        RoutingContext rc = mockRequest("/bootui/api/overview");
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        filter(buffer, Map.of()).handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void skipsBootUiTrafficUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("/app/bootui/api/overview");
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        filter(buffer, Map.of("quarkus.http.root-path", "/app")).handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void skipsBootUiTrafficOnACustomMount() {
        RoutingContext rc = mockRequest("/app/dev-console/api/overview");
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        filter(buffer, Map.of("bootui.path", "/dev-console", "quarkus.http.root-path", "/app"))
                .handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void stillCapturesApplicationTrafficUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("/app/bootui-other/status");
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        completeRequest(filter(buffer, Map.of("quarkus.http.root-path", "/app")), rc);

        assertThat(buffer.snapshot()).hasSize(1);
    }

    @Test
    void stampsEachExchangeWithItsOwnRequestId() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        QuarkusHttpExchangeCaptureFilter filter = filter(buffer, Map.of());

        completeRequest(filter, mockRequest("/orders"));
        completeRequest(filter, mockRequest("/orders"));

        assertThat(buffer.snapshot())
                .extracting(CapturedHttpExchange::requestId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void makesTheRequestContextCurrentWhileTheChainRunsAndRestoresItAfter() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        RoutingContext rc = mockRequest("/orders");
        AtomicReference<CorrelationContext> duringChain = new AtomicReference<>();
        doAnswer(invocation -> {
                    duringChain.set(BootUiCorrelation.current());
                    return null;
                })
                .when(rc)
                .next();

        completeRequest(filter(buffer, Map.of()), rc);

        assertThat(duringChain.get().requestId())
                .isEqualTo(buffer.snapshot().get(0).requestId());
        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void restoresTheThreadWhenTheChainThrows() {
        RoutingContext rc = mockRequest("/orders");
        doThrow(new IllegalStateException("handler failed")).when(rc).next();

        assertThatThrownBy(() -> filter(new HttpExchangeBuffer(10), Map.of()).handle(rc))
                .isInstanceOf(IllegalStateException.class);

        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    /** Runs the filter and then fires the body-end handler it registered, as Vert.x does on response end. */
    private static void completeRequest(QuarkusHttpExchangeCaptureFilter filter, RoutingContext rc) {
        filter.handle(rc);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Void>> captor = ArgumentCaptor.forClass(Handler.class);
        verify(rc).addBodyEndHandler(captor.capture());
        captor.getValue().handle(null);
    }

    private static QuarkusHttpExchangeCaptureFilter filter(HttpExchangeBuffer buffer, Map<String, String> properties) {
        @SuppressWarnings("unchecked")
        Instance<TraceIdSource> traceIdProvider = mock(Instance.class);
        when(traceIdProvider.isResolvable()).thenReturn(false);
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
        return new QuarkusHttpExchangeCaptureFilter(buffer, traceIdProvider, config);
    }

    private static RoutingContext mockRequest(String path) {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.absoluteURI()).thenReturn("http://localhost:8080" + path);
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.request()).thenReturn(request);
        when(rc.response()).thenReturn(response);
        return rc;
    }
}
