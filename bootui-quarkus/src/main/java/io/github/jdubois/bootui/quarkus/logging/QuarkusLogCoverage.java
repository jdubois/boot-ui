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

    private volatile long cachedAt = Long.MIN_VALUE;
    private volatile List<String> cached = List.of();

    @Override
    public String gap(long fromMillis, long toMillis) {
        boolean attached = false;
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            attached |= handler instanceof QuarkusLogTailHandler;
        }
        if (!attached) {
            return "BootUI's log handler is not attached to the root logger";
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
