package io.github.jdubois.bootui.autoconfigure.journal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Names configured root console appenders' instance-held re-entry guards for the agent's shared holder policy.
 * Logback unlocks these by setting false, not null: their first log on a worker is not an application leak. Only the
 * exact guard key is read, never its thread-local value. No agent or an older Logback means no registration.
 */
public final class LogbackThreadLocalHolders implements LoggerContextListener, DisposableBean {

    static final int MAX_APPENDERS = 128;
    static final String GUARD_CLASS = "ch.qos.logback.core.util.ReentryGuard$ReentryGuardImpl";
    static final String GUARD_FIELD = GUARD_CLASS + ".guard";

    private final LoggerContext context;
    private final List<WeakReference<Object>> keys = new ArrayList<>();

    LogbackThreadLocalHolders(LoggerContext context) {
        this.context = context;
        refresh();
        context.addListener(this);
    }

    public static LogbackThreadLocalHolders install() {
        if (!AgentThreadLocals.bound() || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return null;
        }
        return new LogbackThreadLocalHolders(context);
    }

    private synchronized void refresh() {
        clear();
        Iterator<? extends Appender<?>> appenders =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        for (int i = 0; i < MAX_APPENDERS && appenders.hasNext(); i++) {
            Appender<?> appender = appenders.next();
            if (appender instanceof ConsoleAppender<?>) {
                Object key = guardKey(appender);
                if (key != null) {
                    AgentThreadLocals.nameHolder(key, GUARD_FIELD);
                    keys.add(new WeakReference<>(key));
                }
            }
        }
    }

    /** Reads only Logback's exact, version-optional fields, without initializing a class or inspecting a value. */
    static Object guardKey(Appender<?> appender) {
        try {
            Field outer = UnsynchronizedAppenderBase.class.getDeclaredField("reentryGuard");
            if (!outer.trySetAccessible()) {
                return null;
            }
            Object guard = outer.get(appender);
            if (guard == null || !GUARD_CLASS.equals(guard.getClass().getName())) {
                return null;
            }
            Field inner = guard.getClass().getDeclaredField("guard");
            if (inner.getType() != ThreadLocal.class || !inner.trySetAccessible()) {
                return null;
            }
            return inner.get(guard);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // A backend version without these fields stays unresolved, never broadly excluded.
            return null;
        }
    }

    private synchronized void clear() {
        for (WeakReference<Object> reference : keys) {
            Object key = reference.get();
            if (key != null) {
                AgentThreadLocals.nameHolder(key, null);
            }
        }
        keys.clear();
    }

    @Override
    public boolean isResetResistant() {
        return true;
    }

    @Override
    public void onStart(LoggerContext loggerContext) {
        refresh();
    }

    @Override
    public void onReset(LoggerContext loggerContext) {
        clear();
    }

    @Override
    public void onStop(LoggerContext loggerContext) {
        clear();
    }

    @Override
    public void onLevelChange(ch.qos.logback.classic.Logger logger, Level level) {}

    @Override
    public void destroy() {
        clear();
        context.removeListener(this);
    }
}
