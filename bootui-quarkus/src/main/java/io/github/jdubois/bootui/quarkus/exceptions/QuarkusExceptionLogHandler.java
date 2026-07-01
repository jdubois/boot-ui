package io.github.jdubois.bootui.quarkus.exceptions;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.TraceIdProvider;
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
 * with no provider, no context, or a failure, the trace id is simply {@code null}. For web failures the
 * {@code QuarkusExceptionCaptureFilter} resolves the trace id more reliably (on the event loop), and the
 * store's dedup collapses the two into one occurrence.</p>
 */
public final class QuarkusExceptionLogHandler extends Handler {

    private final ExceptionStore store;
    private final InternalPackageMatcher internalPackages;
    private final TraceIdProvider traceIdProvider;
    private final ThreadLocal<Boolean> capturing = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public QuarkusExceptionLogHandler(
            ExceptionStore store, InternalPackageMatcher internalPackages, TraceIdProvider traceIdProvider) {
        this.store = store;
        this.internalPackages = internalPackages;
        this.traceIdProvider = traceIdProvider;
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
            store.record(thrown, Thread.currentThread().getName(), null, null, null, "log", currentTraceId());
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

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
