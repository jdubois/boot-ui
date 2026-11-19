package io.github.jdubois.bootui.autoconfigure.journal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Appender;
import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Whether BootUI's journal appender ({@link RuntimeJournalLogAppender}) sees every {@code WARN}+ log written through
 * Logback ({@code docs/PLAN-v2.md} M5-6): it must still be attached to the root logger, the configuration must not
 * have been reset in the window, and no logger may keep its events from the root logger.
 * The passive projection cannot bound the public native inventory API, so it leaves completeness unassessed.
 * Existing explicit Caught inspection retains its full native inventory read.
 * {@code java.util.logging} must be bridged to SLF4J, as Spring
 * Boot does with {@code jul-to-slf4j}, and no JDK logger may keep its records from that bridge
 * ({@code useParentHandlers=false} with handlers of its own). Read at each outcome read, never on the logging path.
 */
public final class LogbackLogCoverage implements LogCoverage, LoggerContextListener, DisposableBean {

    static final String INVENTORY_UNASSESSED =
            "logger-inventory completeness is unassessed by bounded passive metadata";
    private static final List<String> UNASSESSED_INVENTORY = List.of("(" + INVENTORY_UNASSESSED + ")");
    private static final Class<?> NATIVE_METRICS_FILTER = nativeMetricsFilter();
    private static final int MAX_LOGGERS = 10;
    private static final long CACHE_MILLIS = 1_000L;

    private final LoggerContext context;
    private final java.util.logging.LogManager jul;
    private volatile long resetAt;
    private volatile long deepCachedAt = Long.MIN_VALUE;
    private volatile List<String> deepCached = List.of();

    LogbackLogCoverage(LoggerContext context) {
        this.context = context;
        this.jul = java.util.logging.LogManager.getLogManager();
    }

    /** The coverage of Logback's context, listening for its resets, or {@link LogCoverage#UNREADABLE} without it. */
    public static LogCoverage install() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context) || context.getClass() != LoggerContext.class) {
            return LogCoverage.UNREADABLE;
        }
        LogbackLogCoverage coverage = new LogbackLogCoverage(context);
        context.addListener(coverage);
        return coverage;
    }

    @Override
    public String gap(long fromMillis, long toMillis) {
        String gap = captureGap(fromMillis);
        if (gap != null) {
            return gap;
        }
        List<String> bypassing = bypassingLoggers();
        if (!bypassing.isEmpty()) {
            return bypassing.stream().anyMatch(reason -> reason.startsWith("("))
                    ? "Explicit logging inventory could not establish completeness: " + String.join(", ", bypassing)
                    : "a logger does not pass WARN or ERROR logs to the root logger";
        }
        return null;
    }

    @Override
    public String passiveGap(long fromMillis, long toMillis) {
        String gap = captureGap(fromMillis);
        return gap == null ? INVENTORY_UNASSESSED : gap;
    }

    private String captureGap(long fromMillis) {
        RuntimeJournalLogAppender appender = null;
        var appenders = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            Appender<?> candidate = appenders.next();
            if (candidate instanceof RuntimeJournalLogAppender nativeAppender) {
                appender = nativeAppender;
                break;
            }
        }
        if (appender == null || !appender.isStarted()) {
            return "BootUI's log appender is not attached to the root logger";
        }
        for (var filter : context.getTurboFilterList()) {
            if (filter == null || filter.getClass() != NATIVE_METRICS_FILTER) {
                return "custom logging filters prevent a passive proof of complete log capture";
            }
        }
        if (!appender.getCopyOfAttachedFiltersList().isEmpty()) {
            return "custom logging filters prevent a passive proof of complete log capture";
        }
        long reset = resetAt;
        if (reset != 0L && reset >= fromMillis) {
            return "the logging configuration was reset during its request";
        }
        if (!julBridged(jul)) {
            return "java.util.logging is not bridged to Logback";
        }
        return null;
    }

    /** Whether the JDK's root logger hands its records to SLF4J, by the bridge's class name, without depending on it. */
    static boolean julBridged() {
        return julBridged(java.util.logging.LogManager.getLogManager());
    }

    private static boolean julBridged(java.util.logging.LogManager manager) {
        if (manager.getClass() != java.util.logging.LogManager.class) {
            return false;
        }
        java.util.logging.Logger root = manager.getLogger("");
        if (root == null) {
            return false;
        }
        if (!nativeJulLogger(root)) {
            return false;
        }
        for (java.util.logging.Handler handler : root.getHandlers()) {
            if (handler.getClass().getName().equals("org.slf4j.bridge.SLF4JBridgeHandler")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> bypassingLoggers() {
        long now = System.currentTimeMillis();
        if (deepCachedAt != Long.MIN_VALUE && now - deepCachedAt < CACHE_MILLIS) {
            return deepCached;
        }
        List<String> names = new ArrayList<>();
        // Existing explicit Caught inspection, not the bounded passive Insights path.
        for (Logger logger : context.getLoggerList()) {
            if (!logger.isAdditive()
                    && !InternalPackageMatcher.BOOTUI.matchesName(logger.getName())
                    && logger.iteratorForAppenders().hasNext()
                    && logger.getEffectiveLevel().toInt() <= Level.ERROR_INT) {
                names.add(logger.getName());
                if (names.size() == MAX_LOGGERS) {
                    break;
                }
            }
        }
        if (jul.getClass() != java.util.logging.LogManager.class) {
            names.addAll(UNASSESSED_INVENTORY);
        } else {
            var inventory = jul.getLoggerNames();
            while (names.size() < MAX_LOGGERS && inventory.hasMoreElements()) {
                String name = inventory.nextElement();
                if (name == null || name.isEmpty() || InternalPackageMatcher.BOOTUI.matchesName(name)) {
                    continue;
                }
                java.util.logging.Logger logger = jul.getLogger(name);
                if (logger != null && !nativeJulLogger(logger)) {
                    names.add("(custom JDK logger metadata is not assessed)");
                } else if (logger != null && !logger.getUseParentHandlers() && logger.getHandlers().length > 0) {
                    names.add(name + " (java.util.logging)");
                }
            }
        }
        deepCached = List.copyOf(names);
        deepCachedAt = now;
        return deepCached;
    }

    private static boolean nativeJulLogger(java.util.logging.Logger logger) {
        return logger.getClass() == java.util.logging.Logger.class
                || logger.getClass().getName().equals("java.util.logging.LogManager$RootLogger");
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
        deepCachedAt = Long.MIN_VALUE;
    }

    @Override
    public void onStop(LoggerContext loggerContext) {}

    @Override
    public void onLevelChange(Logger logger, Level level) {
        deepCachedAt = Long.MIN_VALUE;
    }

    private static Class<?> nativeMetricsFilter() {
        try {
            return Class.forName(
                    "io.micrometer.core.instrument.binder.logging.MetricsTurboFilter",
                    false,
                    LogbackMetrics.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    @Override
    public void destroy() {
        context.removeListener(this);
    }
}
