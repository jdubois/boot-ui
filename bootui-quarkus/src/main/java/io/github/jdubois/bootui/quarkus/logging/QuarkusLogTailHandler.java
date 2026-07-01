package io.github.jdubois.bootui.quarkus.logging;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.text.MessageFormat;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Quarkus capture side of the Log Tail panel: a {@code java.util.logging} {@link Handler} attached to the
 * root logger (the JBoss LogManager runs as a {@code java.util.logging.LogManager}) that pushes each
 * record into the shared {@link LogTailBuffer}. It is the Quarkus analogue of the Spring adapter's
 * {@code BootUiLogAppender}; the buffer (capping, replay snapshot, live fan-out) is shared, so both
 * platforms serve the identical {@code /recent} + SSE {@code /stream} wire.
 *
 * <p>Uses only the JDK logging API (no JBoss compile dependency). BootUI's own loggers are dropped so the
 * panel never tails its own internals. The handler is silent on every path and the buffer carries a
 * re-entrancy guard, so capture can never recurse into the logging system.</p>
 */
public final class QuarkusLogTailHandler extends Handler {

    private final LogTailBuffer buffer;
    private final InternalPackageMatcher internalPackages;

    public QuarkusLogTailHandler(LogTailBuffer buffer, InternalPackageMatcher internalPackages) {
        this.buffer = buffer;
        this.internalPackages = internalPackages;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }
        String logger = record.getLoggerName();
        if (internalPackages.matchesName(logger)) {
            return;
        }
        buffer.add(new LogLineDto(
                record.getMillis(),
                QuarkusLoggerProvider.canonicalName(record.getLevel()),
                logger == null ? "ROOT" : logger,
                formatMessage(record),
                Thread.currentThread().getName()));
    }

    private static String formatMessage(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params == null || params.length == 0 || !message.contains("{0")) {
            return message;
        }
        try {
            return MessageFormat.format(message, params);
        } catch (RuntimeException ex) {
            return message;
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
