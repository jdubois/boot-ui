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
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.exceptions.QuarkusExceptionLogHandler;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * White-box binding tests for {@link QuarkusHttpExchangeCaptureFilter}'s self-traffic exclusion: BootUI's
 * own requests must never reach the shared {@code HttpExchangeBuffer} (and therefore never reach the HTTP
 * Exchanges panel or Live Activity correlation), under the default root path, a non-default
 * {@code quarkus.http.root-path}, and a custom {@code bootui.path} mount alike — while application traffic
 * stays captured.
 *
 * <p>It also pins that capture can never disturb the request it observes: a response ended off the event loop
 * (a worker or virtual thread) hands Vert.x's live header map to the event loop before the body-end handler runs,
 * so iterating it there intermittently threw {@code NullPointerException}/{@code NoSuchElementException} into
 * Vert.x, and {@code QuarkusErrorHandler} reported BootUI's failure as the application's.</p>
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
    void readsResponseHeadersFromTheHeadersEndCopyWhenTheResponseEndsOffTheEventLoop() {
        RoutingContext rc = mockRequest("/api/villains/42");
        HttpServerResponse response = rc.response();
        MultiMap committed = MultiMap.caseInsensitiveMultiMap().add("X-Villain", "42");
        when(response.headers()).thenReturn(committed);
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        QuarkusHttpExchangeCaptureFilter filter = filter(buffer, Map.of());

        filter.handle(rc);
        headersEndHandler(rc).handle(null);
        MultiMap beingMutated = concurrentlyMutatedHeaders();
        when(response.headers()).thenReturn(beingMutated);

        assertThatCode(() -> bodyEndHandler(rc).handle(null)).doesNotThrowAnyException();

        verify(beingMutated, never()).iterator();
        assertThat(buffer.snapshot())
                .singleElement()
                .satisfies(
                        exchange -> assertThat(exchange.responseHeaders()).containsEntry("X-Villain", List.of("42")));
    }

    @Test
    void readsTheFinalResponseHeadersOnTheEventLoop() throws Exception {
        RoutingContext rc = mockRequest("/api/villains/42");
        MultiMap live = MultiMap.caseInsensitiveMultiMap().add("X-Villain", "42");
        when(rc.response().headers()).thenReturn(live);
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        filter(buffer, Map.of()).handle(rc);
        headersEndHandler(rc).handle(null);
        // Vert.x appends cookies added through addCookie after headersEndHandler, before the write.
        live.add("Set-Cookie", "SESSION=abc");
        runOnEventLoop(() -> bodyEndHandler(rc).handle(null));

        assertThat(buffer.snapshot())
                .singleElement()
                .satisfies(exchange -> assertThat(exchange.responseHeaders())
                        .containsEntry("X-Villain", List.of("42"))
                        .containsEntry("Set-Cookie", List.of("SESSION=abc")));
    }

    @Test
    void fallsBackToTheHeadersEndCopyWhenTheLiveHeadersFailOnTheEventLoop() throws Exception {
        RoutingContext rc = mockRequest("/api/villains/42");
        HttpServerResponse response = rc.response();
        when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("X-Villain", "42"));
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        filter(buffer, Map.of()).handle(rc);
        headersEndHandler(rc).handle(null);
        MultiMap beingMutated = concurrentlyMutatedHeaders();
        when(response.headers()).thenReturn(beingMutated);
        runOnEventLoop(() -> bodyEndHandler(rc).handle(null));

        assertThat(buffer.snapshot())
                .singleElement()
                .satisfies(
                        exchange -> assertThat(exchange.responseHeaders()).containsEntry("X-Villain", List.of("42")));
    }

    @Test
    void aFailingHeaderReadNeverReachesTheRequestOrTheExceptionsPanel() {
        RoutingContext rc = mockRequest("/api/villains/999999");
        MultiMap beingMutated = concurrentlyMutatedHeaders();
        when(rc.response().headers()).thenReturn(beingMutated);
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        QuarkusHttpExchangeCaptureFilter filter = filter(buffer, Map.of());

        List<LogRecord> logged = captureLogs(() -> {
            filter.handle(rc);
            assertThatCode(() -> headersEndHandler(rc).handle(null)).doesNotThrowAnyException();
            assertThatCode(() -> bodyEndHandler(rc).handle(null)).doesNotThrowAnyException();
        });

        verify(rc).next();
        assertThat(buffer.snapshot())
                .singleElement()
                .satisfies(exchange -> assertThat(exchange.responseHeaders()).isEmpty());
        assertNeverCapturedAsAnApplicationException(logged);
    }

    @Test
    void aFailingRecordNeverReachesTheRequestOrTheExceptionsPanel() {
        RoutingContext rc = mockRequest("/api/villains/5063");
        HttpExchangeBuffer buffer = mock(HttpExchangeBuffer.class);
        doThrow(new IllegalStateException("capture failed")).when(buffer).record(any(CapturedHttpExchange.class));
        QuarkusHttpExchangeCaptureFilter filter = filter(buffer, Map.of());

        List<LogRecord> logged = captureLogs(() -> {
            filter.handle(rc);
            assertThatCode(() -> bodyEndHandler(rc).handle(null)).doesNotThrowAnyException();
        });

        verify(rc).next();
        assertNeverCapturedAsAnApplicationException(logged);
    }

    @Test
    void aFailureBeforeRegistrationStillContinuesTheRequest() {
        RoutingContext rc = mockRequest("/api/villains/42");
        MultiMap beingMutated = concurrentlyMutatedHeaders();
        when(rc.request().headers()).thenReturn(beingMutated);
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);

        assertThatCode(() -> filter(buffer, Map.of()).handle(rc)).doesNotThrowAnyException();

        verify(rc).next();
        verify(rc, never()).addBodyEndHandler(any());
    }

    /** The failure is reported under BootUI's own logger, without a throwable, so log capture ignores it. */
    private static void assertNeverCapturedAsAnApplicationException(List<LogRecord> logged) {
        assertThat(logged).isNotEmpty().allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getThrown()).isNull();
        });
        ExceptionStore store = new ExceptionStore(10, 10, 20);
        QuarkusExceptionLogHandler logCapture = new QuarkusExceptionLogHandler(
                store,
                new InternalPackageMatcher(List.of("io.github.jdubois.bootui.quarkus")),
                null,
                null,
                new SmallRyeConfigBuilder().build());
        logged.forEach(logCapture::publish);
        assertThat(store.totalExceptions()).isZero();
    }

    private static List<LogRecord> captureLogs(Runnable action) {
        Logger logger = Logger.getLogger(QuarkusHttpExchangeCaptureFilter.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    /**
     * A header map whose iteration fails the way Vert.x's {@code HeadersMultiMap} did when the event loop mutated
     * it while the body-end handler iterated it.
     */
    private static MultiMap concurrentlyMutatedHeaders() {
        MultiMap headers = mock(MultiMap.class);
        when(headers.iterator())
                .thenThrow(new NullPointerException("Cannot read field \"key\" because \"this.val$next\" is null"));
        return headers;
    }

    private static void runOnEventLoop(Runnable action) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            vertx.runOnContext(ignored -> {
                try {
                    assertThat(io.vertx.core.Context.isOnEventLoopThread()).isTrue();
                    action.run();
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
            done.get(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static Handler<Void> headersEndHandler(RoutingContext rc) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Void>> captor = ArgumentCaptor.forClass(Handler.class);
        verify(rc).addHeadersEndHandler(captor.capture());
        return captor.getValue();
    }

    private static Handler<Void> bodyEndHandler(RoutingContext rc) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Void>> captor = ArgumentCaptor.forClass(Handler.class);
        verify(rc).addBodyEndHandler(captor.capture());
        return captor.getValue();
    }

    /** Runs the filter and then fires the body-end handler it registered, as Vert.x does on response end. */
    private static void completeRequest(QuarkusHttpExchangeCaptureFilter filter, RoutingContext rc) {
        filter.handle(rc);
        bodyEndHandler(rc).handle(null);
    }

    private static QuarkusHttpExchangeCaptureFilter filter(HttpExchangeBuffer buffer, Map<String, String> properties) {
        @SuppressWarnings("unchecked")
        Instance<TraceIdProvider> traceIdProvider = mock(Instance.class);
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
