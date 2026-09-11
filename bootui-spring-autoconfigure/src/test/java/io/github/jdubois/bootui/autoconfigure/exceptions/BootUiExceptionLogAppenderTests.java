package io.github.jdubois.bootui.autoconfigure.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link BootUiExceptionLogAppender}, including its DevTools-restart cleanup. */
class BootUiExceptionLogAppenderTests {

    @Test
    void prefersCaptureTimeMdcAndDoesNotReadUnrelatedCompletionThreadContext() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        BootUiExceptionLogAppender appender = new BootUiExceptionLogAppender(store);
        appender.setInvocationContextProvider(() -> new InvocationContextProvider.Context("wrong-trace", "call"));
        appender.start();
        ILoggingEvent event = eventWithThrowable(new IllegalStateException("boom"));
        when(event.getMDCPropertyMap()).thenReturn(Map.of("traceId", "original-trace"));
        appender.doAppend(event);
        assertThat(store.groups())
                .singleElement()
                .satisfies(group -> assertThat(store.find(group.fingerprint()).occurrences())
                        .singleElement()
                        .satisfies(
                                occurrence -> assertThat(occurrence.traceId()).isEqualTo("original-trace")));
    }

    @AfterEach
    void detachAppender() {
        BootUiExceptionLogAppender installed = BootUiExceptionLogAppender.find();
        if (installed != null) {
            installed.uninstall();
        }
    }

    @Test
    void appendRecordsLoggedThrowableInStore() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        BootUiExceptionLogAppender appender = new BootUiExceptionLogAppender(store);
        appender.start();

        appender.doAppend(eventWithThrowable(new IllegalStateException("boom")));

        assertThat(store.groups()).hasSize(1);
    }

    @Test
    void uninstallDetachesAppenderFromLoggerContext() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        BootUiExceptionLogAppender appender = BootUiExceptionLogAppender.install(store);
        assertThat(BootUiExceptionLogAppender.find()).isSameAs(appender);

        appender.uninstall();

        // Detached from the JVM-global LoggerContext so a DevTools restart does not leave the old
        // appender — and its discarded ExceptionStore — pinned; install() re-wires a fresh store next start.
        assertThat(BootUiExceptionLogAppender.find()).isNull();
        assertThat(appender.isStarted()).isFalse();
    }

    private static ILoggingEvent eventWithThrowable(Throwable throwable) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(Level.ERROR);
        when(event.getThreadName()).thenReturn("test-thread");
        when(event.getThrowableProxy()).thenReturn(new ThrowableProxy(throwable));
        return event;
    }
}
