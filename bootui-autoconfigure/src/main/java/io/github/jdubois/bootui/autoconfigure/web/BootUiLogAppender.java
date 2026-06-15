package io.github.jdubois.bootui.autoconfigure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

public class BootUiLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String APPENDER_NAME = "BOOTUI_LOG_TAIL";
    private static final int MAX_LINES = 500;

    private final ArrayDeque<LogLineDto> lines = new ArrayDeque<>(MAX_LINES);
    private final CopyOnWriteArrayList<Consumer<LogLineDto>> subscribers = new CopyOnWriteArrayList<>();

    public static synchronized BootUiLogAppender install() {
        BootUiLogAppender existing = find();
        if (existing != null) {
            return existing;
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback LoggerContext is not available");
        }
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        BootUiLogAppender appender = new BootUiLogAppender();
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
        subscribers.clear();
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogLineDto line = new LogLineDto(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                event.getThreadName());
        synchronized (lines) {
            if (lines.size() >= MAX_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
        for (Consumer<LogLineDto> subscriber : subscribers) {
            subscriber.accept(line);
        }
    }

    public List<LogLineDto> getRecentLines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    public Runnable subscribe(Consumer<LogLineDto> consumer) {
        subscribers.add(consumer);
        return () -> subscribers.remove(consumer);
    }

    public record LogLineDto(long timestamp, String level, String logger, String message, String thread) {}
}
