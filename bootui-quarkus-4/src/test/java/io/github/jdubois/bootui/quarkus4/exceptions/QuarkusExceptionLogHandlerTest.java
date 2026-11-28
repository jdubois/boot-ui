package io.github.jdubois.bootui.quarkus4.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link QuarkusExceptionLogHandler}'s self-traffic exclusion. Quarkus' own error handler logs an
 * unhandled request failure under a Quarkus logger name, so the logger-name filter alone does not keep a
 * failure raised while serving BootUI out of the Exceptions panel; the request path must be checked too,
 * under any {@code quarkus.http.root-path} or custom {@code bootui.path} mount.
 */
class QuarkusExceptionLogHandlerTest {

    @Test
    void capturesApplicationRequestFailures() {
        ExceptionStore store = newStore();

        handler(store, routingContext("/orders/42"), Map.of()).publish(failure());

        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(store.groups().get(0).last().requestPath()).isEqualTo("/orders/42");
    }

    @Test
    void capturesFailuresLoggedOutsideAnyRequest() {
        ExceptionStore store = newStore();

        handler(store, null, Map.of()).publish(failure());

        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(store.groups().get(0).last().requestPath()).isNull();
    }

    @Test
    void skipsFailuresLoggedWhileServingBootUiUnderTheDefaultRootPath() {
        ExceptionStore store = newStore();

        handler(store, routingContext("/bootui/api/overview"), Map.of()).publish(failure());

        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsFailuresLoggedWhileServingBootUiUnderANonDefaultRootPath() {
        ExceptionStore store = newStore();

        handler(store, routingContext("/app/bootui/api/overview"), Map.of("quarkus.http.root-path", "/app"))
                .publish(failure());

        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsFailuresLoggedWhileServingACustomBootUiMount() {
        ExceptionStore store = newStore();

        handler(
                        store,
                        routingContext("/app/dev-console/api/overview"),
                        Map.of("bootui.path", "/dev-console", "quarkus.http.root-path", "/app"))
                .publish(failure());

        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void skipsBootUiOwnLoggers() {
        ExceptionStore store = newStore();
        LogRecord record = failure();
        record.setLoggerName("io.github.jdubois.bootui.quarkus4.web.OverviewResource");

        handler(store, routingContext("/orders/42"), Map.of()).publish(record);

        assertThat(store.totalExceptions()).isZero();
    }

    private static ExceptionStore newStore() {
        return new ExceptionStore(10, 10, 20);
    }

    private static LogRecord failure() {
        LogRecord record = new LogRecord(Level.SEVERE, "HTTP Request failed");
        record.setLoggerName("io.quarkus.vertx.http.runtime.QuarkusErrorHandler");
        record.setThrown(new IllegalStateException("boom"));
        return record;
    }

    private static QuarkusExceptionLogHandler handler(
            ExceptionStore store, RoutingContext rc, Map<String, String> properties) {
        CurrentVertxRequest currentVertxRequest = mock(CurrentVertxRequest.class);
        when(currentVertxRequest.getCurrent()).thenReturn(rc);
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
        return new QuarkusExceptionLogHandler(
                store,
                new InternalPackageMatcher(List.of(
                        "io.github.jdubois.bootui.quarkus4",
                        "io.github.jdubois.bootui.engine",
                        "io.github.jdubois.bootui.core")),
                null,
                currentVertxRequest,
                config);
    }

    private static RoutingContext routingContext(String path) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.request()).thenReturn(request);
        return rc;
    }
}
