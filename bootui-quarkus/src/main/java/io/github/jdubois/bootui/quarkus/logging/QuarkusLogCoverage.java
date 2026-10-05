package io.github.jdubois.bootui.quarkus.logging;

import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Whether BootUI's {@link QuarkusLogTailHandler} sees every {@code WARN}+ log written through the JBoss LogManager
 * ({@code docs/PLAN-v2.md} M5-6): it must be attached to the root logger, and no {@code WARN}-enabled logger may keep
 * its records from the root logger ({@code use-parent-handlers=false} with handlers of its own). Uses only the JDK
 * logging API, read at each outcome read, never on the logging path.
 */
public final class QuarkusLogCoverage implements LogCoverage {

    /** The most bypassing loggers named. */
    static final int MAX_LOGGERS = 10;

    /** How long the bypassing loggers are kept between reads. */
    static final long CACHE_MILLIS = 1_000L;

    /**
     * When BootUI's handler was last installed or removed, by the wall clock, as at a live reload; 0 while never. Logs
     * written around it may have reached no handler of BootUI's.
     */
    private static volatile long handlerChangedAt;

    /** The handler that was attached at the last read, to notice a swap made outside {@link QuarkusLogTailCapture}. */
    private volatile Handler lastSeen;

    private volatile long cachedAt = Long.MIN_VALUE;
    private volatile List<String> cached = List.of();

    /** BootUI's handler was installed or removed now. */
    static void handlerChanged() {
        handlerChangedAt = System.currentTimeMillis();
    }

    @Override
    public String gap(long fromMillis, long toMillis) {
        Handler attached = null;
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof QuarkusLogTailHandler) {
                attached = handler;
            }
        }
        if (attached == null) {
            return "BootUI's log handler is not attached to the root logger";
        }
        Handler previous = lastSeen;
        if (previous != null && previous != attached) {
            handlerChanged();
        }
        lastSeen = attached;
        long changed = handlerChangedAt;
        if (changed != 0L && changed >= fromMillis) {
            return "BootUI's log handler was replaced during its request, as at a live reload";
        }
        if (!bypassingLoggers().isEmpty()) {
            return "a logger does not pass WARN logs to the root logger";
        }
        return null;
    }

    @Override
    public List<String> bypassingLoggers() {
        long now = System.currentTimeMillis();
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        try {
            for (String name : Collections.list(LogManager.getLogManager().getLoggerNames())) {
                if (name == null || name.isEmpty() || InternalPackageMatcher.BOOTUI.matchesName(name)) {
                    continue;
                }
                Logger logger = LogManager.getLogManager().getLogger(name);
                if (logger == null
                        || logger.getUseParentHandlers()
                        || logger.getHandlers().length == 0
                        || !logger.isLoggable(Level.WARNING)) {
                    continue;
                }
                names.add(name);
                if (names.size() == MAX_LOGGERS) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            names.add("(the log manager's loggers could not be read)");
        }
        cached = List.copyOf(names);
        cachedAt = now;
        return cached;
    }
}
