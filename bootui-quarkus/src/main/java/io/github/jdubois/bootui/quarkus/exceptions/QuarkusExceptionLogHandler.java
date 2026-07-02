package io.github.jdubois.bootui.quarkus.exceptions;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Quarkus log-side capture for the Exceptions panel: a {@code java.util.logging} {@link Handler} attached
 * to the root logger (the JBoss LogManager runs as a {@code java.util.logging.LogManager}) that feeds any
 * log record carrying a throwable into the shared {@link ExceptionStore}. It is the Quarkus analogue of
 * the Spring adapter's {@code BootUiExceptionLogAppender}; the store (grouping, cause-chain dedup,
 * capping) is shared, so both platforms serve the identical wire.
 *
 * <p>BootUI's own loggers are dropped so the panel never captures its internals. A {@link ThreadLocal}
 * re-entrancy guard plus the store's own dedup means capture can never recurse into the logging system,
 * and every path is silent so a misbehaving log can never disrupt the application.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdProvider} is present it stamps a best-effort trace id on each
 * captured throwable so a logged failure can nest under its request in the Live Activity timeline — when
 * the logging thread still carries the request's OpenTelemetry context. It is nullable and fully guarded:
 * with no provider, no context, or a failure, the trace id is simply {@code null}.</p>
 *
 * <p>It also resolves the request method/path for the same reason {@code BootUiExceptionHandlerResolver}
 * captures them directly on Spring: Quarkus's default error handling ({@code QuarkusErrorHandler}) logs an
 * unhandled request failure <em>synchronously</em>, before the response — and so before
 * {@code QuarkusExceptionCaptureFilter}'s {@code addBodyEndHandler} callback — ever runs. Since
 * {@link ExceptionStore#record} dedups by throwable identity and keeps only the first feeder's context,
 * that ordering means the filter's richer web context is silently discarded and every Quarkus exception
 * would otherwise carry a {@code null} method/path. Reading the CDI-provided {@link CurrentVertxRequest}
 * here — request-scoped, and populated by Quarkus before the resource method that can fail — closes that
 * gap by resolving the context at the point that actually wins the race. It is nullable and fully guarded:
 * with no provider, no active request scope (e.g. a background/scheduled failure), or a failure, the
 * method/path are simply {@code null}, same as before.</p>
 *
 * <p>The handler (JAX-RS resource class + method) is resolved the same way, via {@link
 * QuarkusResourceHandlers#currentHandler()} — RESTEasy Reactive's own current-request accessor, populated
 * and cleared in lockstep with the same CDI request scope {@link CurrentVertxRequest} follows, so it is
 * available at exactly the point this handler already reads method/path from. See that class's Javadoc for
 * why the underlying API is a reasonably stable extension point rather than a fragile internal.</p>
 */
public final class QuarkusExceptionLogHandler extends Handler {

    private final ExceptionStore store;
    private final InternalPackageMatcher internalPackages;
    private final TraceIdProvider traceIdProvider;
    private final CurrentVertxRequest currentVertxRequest;
    private final ThreadLocal<Boolean> capturing = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public QuarkusExceptionLogHandler(
            ExceptionStore store,
            InternalPackageMatcher internalPackages,
            TraceIdProvider traceIdProvider,
            CurrentVertxRequest currentVertxRequest) {
        this.store = store;
        this.internalPackages = internalPackages;
        this.traceIdProvider = traceIdProvider;
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || Boolean.TRUE.equals(capturing.get())) {
            return;
        }
        Throwable thrown = record.getThrown();
        if (thrown == null || internalPackages.matchesName(record.getLoggerName())) {
            return;
        }
        capturing.set(Boolean.TRUE);
        try {
            RoutingContext rc = currentRoutingContext();
            String method = rc == null ? null : rc.request().method().name();
            String path = rc == null ? null : rc.normalizedPath();
            String handler = QuarkusResourceHandlers.currentHandler();
            store.record(thrown, Thread.currentThread().getName(), method, path, handler, "log", currentTraceId());
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with the application's logging.
        } finally {
            capturing.set(Boolean.FALSE);
        }
    }

    private String currentTraceId() {
        if (traceIdProvider == null) {
            return null;
        }
        try {
            return traceIdProvider.currentTraceId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The active request's routing context, or {@code null} when none is current (no request scope active
     * — e.g. a scheduled task or startup failure — or the request-scoped bean has already been torn down).
     */
    private RoutingContext currentRoutingContext() {
        if (currentVertxRequest == null) {
            return null;
        }
        try {
            return currentVertxRequest.getCurrent();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
