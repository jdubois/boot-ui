package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * White-box binding tests for {@link QuarkusExceptionCaptureFilter}'s self-traffic exclusion: a failure on
 * BootUI's own surface must never reach the shared {@code ExceptionStore}, under the default root path, a
 * non-default {@code quarkus.http.root-path}, and a custom {@code bootui.path} mount alike — while an
 * application request failure stays captured.
 */
class QuarkusExceptionCaptureFilterTest {

    @Test
    void capturesApplicationRequestFailures() {
        RoutingContext rc = mockRequest("/orders/42", new IllegalStateException("boom"));
        ExceptionStore store = newStore();

        completeRequest(filter(store, Map.of()), rc);

        verify(rc).next();
        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(store.groups().get(0).last().requestPath()).isEqualTo("/orders/42");
    }

    @Test
    void ignoresRequestsThatDidNotFail() {
        RoutingContext rc = mockRequest("/orders/42", null);
        ExceptionStore store = newStore();

        completeRequest(filter(store, Map.of()), rc);

        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsBootUiFailuresUnderTheDefaultRootPath() {
        RoutingContext rc = mockRequest("/bootui/api/overview", new IllegalStateException("boom"));
        ExceptionStore store = newStore();

        filter(store, Map.of()).handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsBootUiFailuresUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("/app/bootui/api/overview", new IllegalStateException("boom"));
        ExceptionStore store = newStore();

        filter(store, Map.of("quarkus.http.root-path", "/app")).handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsBootUiFailuresOnACustomMount() {
        RoutingContext rc = mockRequest("/app/dev-console/api/overview", new IllegalStateException("boom"));
        ExceptionStore store = newStore();

        filter(store, Map.of("bootui.path", "/dev-console", "quarkus.http.root-path", "/app"))
                .handle(rc);

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void stillCapturesApplicationFailuresUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("/app/bootui-other/status", new IllegalStateException("boom"));
        ExceptionStore store = newStore();

        completeRequest(filter(store, Map.of("quarkus.http.root-path", "/app")), rc);

        assertThat(store.totalExceptions()).isEqualTo(1);
    }

    @Test
    void aFailingCaptureNeverReachesTheRequest() {
        RoutingContext rc = mockRequest("/orders/42", new IllegalStateException("boom"));
        ExceptionStore store = mock(ExceptionStore.class);
        doThrow(new IllegalStateException("capture failed"))
                .when(store)
                .record(any(), any(), any(), any(), any(), any(), any());
        QuarkusExceptionCaptureFilter filter = filter(store, Map.of());

        filter.handle(rc);

        verify(rc).next();
        assertThatCode(() -> bodyEndHandler(rc).handle(null)).doesNotThrowAnyException();
    }

    @Test
    void aFailureBeforeRegistrationStillContinuesTheRequest() {
        RoutingContext rc = mockRequest("/orders/42", null);
        when(rc.request().method()).thenThrow(new IllegalStateException("request recycled"));

        assertThatCode(() -> filter(newStore(), Map.of()).handle(rc)).doesNotThrowAnyException();

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
    }

    private static Handler<Void> bodyEndHandler(RoutingContext rc) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Void>> captor = ArgumentCaptor.forClass(Handler.class);
        verify(rc).addBodyEndHandler(captor.capture());
        return captor.getValue();
    }

    /** Runs the filter and then fires the body-end handler it registered, as Vert.x does on response end. */
    private static void completeRequest(QuarkusExceptionCaptureFilter filter, RoutingContext rc) {
        filter.handle(rc);
        bodyEndHandler(rc).handle(null);
    }

    private static ExceptionStore newStore() {
        return new ExceptionStore(10, 10, 20);
    }

    private static QuarkusExceptionCaptureFilter filter(ExceptionStore store, Map<String, String> properties) {
        @SuppressWarnings("unchecked")
        Instance<TraceIdProvider> traceIdProvider = mock(Instance.class);
        when(traceIdProvider.isResolvable()).thenReturn(false);
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
        return new QuarkusExceptionCaptureFilter(store, traceIdProvider, config);
    }

    private static RoutingContext mockRequest(String path, Throwable failure) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.request()).thenReturn(request);
        when(rc.failure()).thenReturn(failure);
        return rc;
    }
}
