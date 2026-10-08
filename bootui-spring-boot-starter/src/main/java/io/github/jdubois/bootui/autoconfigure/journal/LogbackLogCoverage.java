package io.github.jdubois.bootui.autoconfigure.journal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Appender;
import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Whether BootUI's journal appender ({@link RuntimeJournalLogAppender}) sees every {@code WARN}+ log written through
 * Logback ({@code docs/PLAN-v2.md} M5-6): it must still be attached to the root logger, the configuration must not
 * have been reset in the window, and no {@code WARN}-enabled logger may keep its events from the root logger
 * ({@code additivity="false"} with appenders of its own). {@code java.util.logging} must be bridged to SLF4J, as Spring
 * Boot does with {@code jul-to-slf4j}, and no JDK logger may keep its records from that bridge
 * ({@code useParentHandlers=false} with handlers of its own). Read at each outcome read, never on the logging path.
 */
public final class LogbackLogCoverage implements LogCoverage, LoggerContextListener, DisposableBean {

    /** The most bypassing loggers named. */
    static final int MAX_LOGGERS = 10;

    /** How long the bypassing loggers are kept between reads. */
    static final long CACHE_MILLIS = 1_000L;

    private final LoggerContext context;
    private volatile long resetAt;
    private volatile long cachedAt = Long.MIN_VALUE;
    private volatile List<String> cached = List.of();

    LogbackLogCoverage(LoggerContext context) {
        this.context = context;
    }

    /** The coverage of Logback's context, listening for its resets, or {@link LogCoverage#UNREADABLE} without it. */
    public static LogCoverage install() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return LogCoverage.UNREADABLE;
        }
        LogbackLogCoverage coverage = new LogbackLogCoverage(context);
        context.addListener(coverage);
        return coverage;
    }

    @Override
    public String gap(long fromMillis, long toMillis) {
        Appender<?> appender = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                .getAppender(RuntimeJournalLogAppender.APPENDER_NAME);
        if (appender == null || !appender.isStarted()) {
            return "BootUI's log appender is not attached to the root logger";
        }
        long reset = resetAt;
        if (reset != 0L && reset >= fromMillis) {
            return "the logging configuration was reset during its request";
        }
        if (!julBridged()) {
            return "java.util.logging is not bridged to Logback";
        }
        if (!bypassingLoggers().isEmpty()) {
            return "a logger does not pass WARN logs to the root logger";
        }
        return null;
    }

    /** Whether the JDK's root logger hands its records to SLF4J, by the bridge's class name, without depending on it. */
    static boolean julBridged() {
        for (java.util.logging.Handler handler :
                java.util.logging.Logger.getLogger("").getHandlers()) {
            if (handler.getClass().getName().equals("org.slf4j.bridge.SLF4JBridgeHandler")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> bypassingLoggers() {
        long now = System.currentTimeMillis();
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        for (Logger logger : context.getLoggerList()) {
            String name = logger.getName();
            if (logger.isAdditive()
                    || InternalPackageMatcher.BOOTUI.matchesName(name)
                    || !logger.iteratorForAppenders().hasNext()
                    || !logger.isEnabledFor(Level.WARN)) {
                continue;
            }
            names.add(name);
            if (names.size() == MAX_LOGGERS) {
                break;
            }
        }
        julBypassing(names);
        cached = List.copyOf(names);
        cachedAt = now;
        return cached;
    }

    /** Adds the JDK loggers that keep their {@code WARNING} records from the root logger's bridge. */
    private static void julBypassing(List<String> names) {
        try {
            java.util.logging.LogManager manager = java.util.logging.LogManager.getLogManager();
            for (String name : java.util.Collections.list(manager.getLoggerNames())) {
                if (names.size() >= MAX_LOGGERS) {
                    return;
                }
                if (name == null || name.isEmpty() || InternalPackageMatcher.BOOTUI.matchesName(name)) {
                    continue;
                }
                java.util.logging.Logger logger = manager.getLogger(name);
                if (logger != null
                        && !logger.getUseParentHandlers()
                        && logger.getHandlers().length > 0
                        && logger.isLoggable(java.util.logging.Level.WARNING)) {
                    names.add(name + " (java.util.logging)");
                }
            }
        } catch (RuntimeException ex) {
            names.add("(the JDK's loggers could not be read)");
        }
    }

    @Override
    public boolean isResetResistant() {
        return true;
    }

    @Override
    public void onStart(LoggerContext loggerContext) {}

    @Override
    public void onReset(LoggerContext loggerContext) {
        resetAt = System.currentTimeMillis();
        cachedAt = Long.MIN_VALUE;
    }

    @Override
    public void onStop(LoggerContext loggerContext) {}

    @Override
    public void onLevelChange(Logger logger, Level level) {
        cachedAt = Long.MIN_VALUE;
    }

    @Override
    public void destroy() {
        context.removeListener(this);
    }
}
