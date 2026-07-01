package io.github.jdubois.bootui.autoconfigure.exceptions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Logback appender that feeds the {@link ExceptionStore} with any log event that carries a
 * throwable, so exceptions that are logged rather than propagated to an MVC handler (scheduled
 * tasks, async work, filters, manual {@code log.error("...", ex)}) still appear in the panel.
 *
 * <p>It records the original {@link Throwable} carried by logback's {@link ThrowableProxy}; the
 * store deduplicates by throwable identity, so an exception both handled by Spring MVC and logged is
 * counted only once.</p>
 */
public class BootUiExceptionLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String APPENDER_NAME = "BOOTUI_EXCEPTIONS";

    private final ExceptionStore store;

    public BootUiExceptionLogAppender(ExceptionStore store) {
        this.store = store;
    }

    public static synchronized BootUiExceptionLogAppender install(ExceptionStore store) {
        BootUiExceptionLogAppender existing = find();
        if (existing != null) {
            return existing;
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback LoggerContext is not available");
        }
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        BootUiExceptionLogAppender appender = new BootUiExceptionLogAppender(store);
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
        return appender;
    }

    public static BootUiExceptionLogAppender find() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return null;
        }
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> appender = rootLogger.getAppender(APPENDER_NAME);
        if (appender instanceof BootUiExceptionLogAppender bootUiExceptionLogAppender) {
            return bootUiExceptionLogAppender;
        }
        return null;
    }

    /**
     * Detaches this appender from the (JVM-global) Logback {@link LoggerContext} and stops it. Invoked
     * as the bean's destroy method so a Spring Boot DevTools restart does not leave the appender — and,
     * through it, the discarded {@link ExceptionStore} and its class loader — pinned by the surviving
     * {@code LoggerContext}, and so the new context's store is wired in cleanly on the next start.
     */
    public void uninstall() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (!(proxy instanceof ThrowableProxy throwableProxy)) {
            return;
        }
        Throwable throwable = throwableProxy.getThrowable();
        if (throwable == null) {
            return;
        }
        store.record(throwable, event.getThreadName(), null, null, null, "log");
    }
}
