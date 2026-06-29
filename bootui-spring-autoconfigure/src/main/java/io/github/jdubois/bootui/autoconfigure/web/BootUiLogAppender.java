package io.github.jdubois.bootui.autoconfigure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Thin Logback adapter that feeds the framework-neutral {@link LogTailBuffer}. It builds a core
 * {@link LogLineDto} directly from each event and pushes it into the shared buffer, which owns the
 * capping, the replay snapshot and the live-subscriber fan-out shared with the Quarkus adapter.
 */
public class BootUiLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String APPENDER_NAME = "BOOTUI_LOG_TAIL";

    private final LogTailBuffer buffer;

    public BootUiLogAppender(LogTailBuffer buffer) {
        this.buffer = buffer;
    }

    public static synchronized BootUiLogAppender install(LogTailBuffer buffer) {
        BootUiLogAppender existing = find();
        if (existing != null) {
            return existing;
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback LoggerContext is not available");
        }
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        BootUiLogAppender appender = new BootUiLogAppender(buffer);
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
        return appender;
    }

    public static BootUiLogAppender find() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return null;
        }
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> appender = rootLogger.getAppender(APPENDER_NAME);
        if (appender instanceof BootUiLogAppender bootUiLogAppender) {
            return bootUiLogAppender;
        }
        return null;
    }

    /**
     * Detaches this appender from the (JVM-global) Logback {@link LoggerContext} and stops it, so a
     * Spring Boot DevTools restart does not leave the appender — and the discarded context's stream
     * subscribers behind it — pinned by the surviving {@code LoggerContext} on every live reload.
     */
    public void uninstall() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }

    public LogTailBuffer buffer() {
        return buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        buffer.add(new LogLineDto(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                event.getThreadName()));
    }
}
