package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Whether BootUI's journal appender sees every WARN+ log, for the caught-exception outcomes (docs/PLAN-v2.md M5-6):
 * attached to the root logger, no reset in the window, and no WARN-enabled logger keeping its events from the root.
 */
class LogbackLogCoverageTests {

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private RuntimeJournalLogAppender appender;
    private LogbackLogCoverage coverage;
    private Logger bypassing;
    private ListAppender<ILoggingEvent> own;

    @AfterEach
    void restore() {
        if (appender != null) {
            appender.destroy();
        }
        if (coverage != null) {
            coverage.destroy();
        }
        if (bypassing != null) {
            bypassing.setAdditive(true);
            bypassing.detachAppender(own);
            bypassing.setLevel(null);
        }
    }

    @Test
    void coverageIsCompleteOnlyWhileTheAppenderIsAttachedAndNoLoggerBypassesTheRoot() {
        coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
        assertThat(coverage.gap(0, Long.MAX_VALUE)).contains("not attached");

        appender = RuntimeJournalLogAppender.install(event -> true);
        assertThat(coverage.gap(0, Long.MAX_VALUE))
                .isEqualTo(LogbackLogCoverage.julBridged() ? null : "java.util.logging is not bridged to Logback");
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        assertThat(coverage.gap(0, Long.MAX_VALUE)).isNull();
        assertThat(coverage.bypassingLoggers()).isEmpty();

        bypassing = context.getLogger("com.example.audit");
        own = new ListAppender<>();
        own.start();
        bypassing.addAppender(own);
        bypassing.setAdditive(false);
        coverage.onLevelChange(bypassing, Level.INFO);

        assertThat(coverage.bypassingLoggers()).containsExactly("com.example.audit");
        assertThat(coverage.gap(0, Long.MAX_VALUE)).contains("does not pass WARN logs");

        bypassing.setLevel(Level.ERROR);
        coverage.onLevelChange(bypassing, Level.ERROR);
        assertThat(coverage.bypassingLoggers()).isEmpty();
    }

    @Test
    void aJdkLoggerKeepingItsRecordsFromTheBridgeBypassesTheRoot() {
        java.util.logging.Logger jdk = java.util.logging.Logger.getLogger("com.example.jdk");
        java.util.logging.Handler own = new java.util.logging.ConsoleHandler();
        jdk.addHandler(own);
        jdk.setUseParentHandlers(false);
        try {
            coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
            assertThat(coverage.bypassingLoggers()).contains("com.example.jdk (java.util.logging)");
        } finally {
            jdk.removeHandler(own);
            jdk.setUseParentHandlers(true);
        }
    }

    @Test
    void aResetDuringTheWindowIsAGap() {
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        appender = RuntimeJournalLogAppender.install(event -> true);
        coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
        long before = System.currentTimeMillis() - 1_000;

        coverage.onReset(context);

        assertThat(coverage.gap(before, Long.MAX_VALUE)).contains("reset");
        assertThat(coverage.gap(System.currentTimeMillis() + 1_000, Long.MAX_VALUE))
                .isNull();
    }
}
