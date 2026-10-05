package io.github.jdubois.bootui.autoconfigure.journal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.ThrowableMarks;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Records each application {@code WARN} and {@code ERROR} log event in the runtime journal ({@code docs/PLAN-v2.md}
 * §5.2), with its unformatted template and the request it was logged in, never its formatted message. Installed on
 * Logback's root logger when the application context starts, unlike Log Tail's appender, which waits for the panel to
 * open, and removed when the context closes, so a DevTools restart never leaves it behind. BootUI's own loggers are
 * skipped.
 */
public final class RuntimeJournalLogAppender extends AppenderBase<ILoggingEvent> implements DisposableBean {

    static final String APPENDER_NAME = "BOOTUI_RUNTIME_JOURNAL";

    private final RuntimeEventSink journal;

    RuntimeJournalLogAppender(RuntimeEventSink journal) {
        this.journal = journal;
    }

    /** Installs an appender on Logback's root logger, or returns {@code null} when Logback is not the logger. */
    public static RuntimeJournalLogAppender install(RuntimeEventSink journal) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return null;
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) instanceof RuntimeJournalLogAppender previous) {
            previous.destroy();
        }
        RuntimeJournalLogAppender appender = new RuntimeJournalLogAppender(journal);
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        String logger = event.getLoggerName();
        if (InternalPackageMatcher.BOOTUI.matchesName(logger)) {
            return;
        }
        try {
            IThrowableProxy throwable = event.getThrowableProxy();
            journal.offer(RuntimeEvent.of(
                    JournalSource.LOG,
                    event.getTimeStamp(),
                    -1,
                    BootUiCorrelation.current(),
                    event.getThreadName(),
                    null,
                    event.getLevel().isGreaterOrEqual(Level.ERROR),
                    new LogPayload(
                            logger,
                            event.getLevel().toString(),
                            event.getMessage(),
                            throwable == null ? null : throwable.getClassName(),
                            throwable instanceof ThrowableProxy proxy
                                    ? ThrowableMarks.of(proxy.getThrowable())
                                    : null)));
        } catch (RuntimeException ex) {
            // Recording never disturbs the application's logging.
        }
    }

    @Override
    public void destroy() {
        if (getContext() instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }
}
