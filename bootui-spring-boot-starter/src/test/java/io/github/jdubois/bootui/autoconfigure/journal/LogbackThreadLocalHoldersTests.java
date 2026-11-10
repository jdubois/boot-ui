package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LogbackThreadLocalHoldersTests {

    @Test
    void aFirstConsoleLogLeavesItsGuardSetButOnlyThatKeyIsNamedAndRegistrationIsRemovedOnShutdown() {
        LoggerContext context = new LoggerContext();
        ConsoleAppender<ILoggingEvent> console = console(context);
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(console);
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            LogbackThreadLocalHolders holders = new LogbackThreadLocalHolders(context);
            Object guard = LogbackThreadLocalHolders.guardKey(console);
            assertThat(guard).isInstanceOf(ThreadLocal.class);
            agent.verify(() -> AgentThreadLocals.nameHolder(guard, LogbackThreadLocalHolders.GUARD_FIELD));

            ThreadLocal<String> tenant = new ThreadLocal<>();
            tenant.set("tenant-secret-nulled");
            tenant.set(null);
            assertThat(((ThreadLocal<?>) guard).get()).isNull();
            root.warn("first console log on this worker");

            assertThat(tenant.get()).isNull();
            assertThat(((ThreadLocal<?>) guard).get())
                    .as("Logback unlocks with false, which is still non-null")
                    .isEqualTo(Boolean.FALSE);
            assertThat(LogbackThreadLocalHolders.guardKey(new ListAppender<>())).isNull();
            holders.destroy();
            agent.verify(() -> AgentThreadLocals.nameHolder(guard, null));
            agent.verifyNoMoreInteractions();
        } finally {
            context.stop();
        }
    }

    @Test
    void loggingResetsRemoveOldKeysAndAStartedContextNamesOnlyTheReplacementConsoleGuard() {
        LoggerContext context = new LoggerContext();
        ConsoleAppender<ILoggingEvent> old = console(context);
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(old);
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            LogbackThreadLocalHolders holders = new LogbackThreadLocalHolders(context);
            Object previous = LogbackThreadLocalHolders.guardKey(old);
            context.reset();
            agent.verify(() -> AgentThreadLocals.nameHolder(previous, null));

            ConsoleAppender<ILoggingEvent> replacement = console(context);
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(replacement);
            context.start();
            Object current = LogbackThreadLocalHolders.guardKey(replacement);
            assertThat(current).isNotSameAs(previous);
            agent.verify(() -> AgentThreadLocals.nameHolder(current, LogbackThreadLocalHolders.GUARD_FIELD));
            holders.destroy();
        } finally {
            context.stop();
        }
    }

    @Test
    void withoutAnAgentNothingIsInstalled() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            assertThat(LogbackThreadLocalHolders.install()).isNull();
            agent.verify(AgentThreadLocals::bound);
            agent.verifyNoMoreInteractions();
        }
    }

    private static ConsoleAppender<ILoggingEvent> console(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.start();
        appender.setOutputStream(new ByteArrayOutputStream());
        return appender;
    }
}
