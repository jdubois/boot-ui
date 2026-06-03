package io.github.jdubois.bootui.autoconfigure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        LogLineDto line = new LogLineDto(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                event.getThreadName(),
                mdcValue(mdc, "traceId", "trace_id"),
                mdcValue(mdc, "spanId", "span_id"));
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

    private static String mdcValue(Map<String, String> mdc, String... keys) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = mdc.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    public record LogLineDto(
            long timestamp,
            String level,
            String logger,
            String message,
            String thread,
            String traceId,
            String spanId) {}
}
