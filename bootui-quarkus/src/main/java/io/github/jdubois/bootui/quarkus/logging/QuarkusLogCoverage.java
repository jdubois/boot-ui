package io.github.jdubois.bootui.quarkus.logging;

import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.jboss.logmanager.LogContext;

/**
 * Whether BootUI's {@link QuarkusLogTailHandler} sees every {@code WARN}+ log written through the JBoss LogManager
 * ({@code docs/PLAN-v2.md} M5-6): it must be attached to the root logger, and no {@code WARN}- or {@code ERROR}-enabled logger may keep
 * its records from the root logger ({@code use-parent-handlers=false} with handlers of its own). Reads the root held
 * by capture startup and its native context directly, never an application's log-context selector or filter.
 */
public final class QuarkusLogCoverage implements LogCoverage {

    /** The most bypassing loggers named. */
    static final int MAX_LOGGERS = 10;

    static final int MAX_VISITED_LOGGERS = 4096;
    private static final String INVENTORY_LIMIT =
            "(logger inventory exceeds 4096 entries; capture completeness is unassessed)";

    /** How long the bypassing loggers are kept between reads. */
    static final long CACHE_MILLIS = 1_000L;

    /**
     * When BootUI's handler was last installed or removed, by the wall clock, as at a live reload; 0 while never. Logs
     * written around it may have reached no handler of BootUI's.
     */
    private volatile long handlerChangedAt;

    private volatile Logger captureRoot;

    /** The handler that was attached at the last read, to notice a swap made outside {@link QuarkusLogTailCapture}. */
    private volatile Handler lastSeen;

    private volatile long cachedAt = Long.MIN_VALUE;
    private volatile List<String> cached = List.of();

    /** BootUI's handler was installed or removed now. */
    private void handlerChanged() {
        handlerChangedAt = System.currentTimeMillis();
    }

    /** The root already selected by the capture lifecycle, never selected through application policy on a read. */
    synchronized void handlerChanged(Logger root, QuarkusLogTailHandler installed) {
        captureRoot = root;
        handlerChanged();
        lastSeen = installed;
        cachedAt = Long.MIN_VALUE;
        cached = List.of();
    }

    @Override
    public synchronized String gap(long fromMillis, long toMillis) {
        Logger root = captureRoot;
        if (root == null) {
            return "BootUI's log handler is not attached to the root logger";
        }
        if (!nativeLogger(root)) {
            return "the root logger's native metadata cannot be read passively";
        }
        String routing = routingGap(root);
        if (routing != null) {
            return routing;
        }
        QuarkusLogTailHandler attached = null;
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof QuarkusLogTailHandler nativeHandler) {
                attached = nativeHandler;
            }
        }
        if (attached == null) {
            return "BootUI's log handler is not attached to the root logger";
        }
        if (attached.capturePaused()) {
            return "BootUI's log handler is paused for low-footprint mode";
        }
        if (attached.getFilter() != null || attached.getLevel().intValue() > Level.WARNING.intValue()) {
            return "BootUI's log handler has filtering that prevents a passive proof of complete capture";
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
        List<String> bypassing = bypassingLoggers();
        if (!bypassing.isEmpty()) {
            for (String reason : bypassing) {
                if (reason.startsWith("(")) {
                    return "log capture completeness is unassessed: " + reason;
                }
            }
            return "a logger does not pass WARN logs to the root logger";
        }
        return null;
    }

    @Override
    public synchronized List<String> bypassingLoggers() {
        Logger root = captureRoot;
        String routing = routingGap(root);
        if (routing != null) {
            return List.of(routing);
        }
        long now = System.currentTimeMillis();
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        try {
            LogManager manager = LogManager.getLogManager();
            LogContext nativeContext =
                    root instanceof org.jboss.logmanager.Logger nativeRoot ? nativeRoot.getLogContext() : null;
            if (nativeContext == null && manager.getClass() != LogManager.class) {
                cached = List.of("(custom log manager metadata is not assessed)");
                cachedAt = now;
                return cached;
            }
            var inventory = nativeContext == null ? manager.getLoggerNames() : nativeContext.getLoggerNames();
            int visited = 0;
            while (inventory.hasMoreElements()) {
                if (names.size() >= MAX_LOGGERS) {
                    break;
                }
                if (visited >= MAX_VISITED_LOGGERS) {
                    names.add(INVENTORY_LIMIT);
                    break;
                }
                String name = inventory.nextElement();
                visited++;
                if (name == null || name.isEmpty() || InternalPackageMatcher.BOOTUI.matchesName(name)) {
                    continue;
                }
                Logger logger = nativeContext == null ? manager.getLogger(name) : nativeContext.getLoggerIfExists(name);
                if (!nativeLogger(logger)) {
                    if (logger != null) {
                        names.add("(custom logger metadata is not assessed)");
                    }
                    continue;
                }
                if (logger.getUseParentHandlers() || logger.getHandlers().length == 0) {
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

    private static String routingGap(Logger root) {
        if (root instanceof org.jboss.logmanager.Logger nativeRoot
                && (LogContext.getLogContextSelector() != LogContext.DEFAULT_LOG_CONTEXT_SELECTOR
                        || nativeRoot.getLogContext() != LogContext.getSystemLogContext())) {
            return "the current log-context routing is not passively proven to reach BootUI's held root";
        }
        return null;
    }

    private static boolean nativeLogger(Logger logger) {
        return logger != null
                && (logger.getClass() == Logger.class
                        || logger.getClass().getName().equals("java.util.logging.LogManager$RootLogger")
                        || logger.getClass().getName().equals("org.jboss.logmanager.Logger"));
    }
}
