package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

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
    void capturePresenceAndInventoryUncertaintyRemainDistinctAcrossLoggerLevels() {
        coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
        assertThat(coverage.passiveGap(0, Long.MAX_VALUE)).contains("not attached");

        appender = RuntimeJournalLogAppender.install(event -> true);
        assertThat(coverage.passiveGap(0, Long.MAX_VALUE))
                .isEqualTo(
                        LogbackLogCoverage.julBridged()
                                ? LogbackLogCoverage.INVENTORY_UNASSESSED
                                : "java.util.logging is not bridged to Logback");
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        assertThat(coverage.passiveGap(0, Long.MAX_VALUE)).contains("logger-inventory completeness is unassessed");
        assertThat(coverage.bypassingLoggers()).isEmpty();
        assertThat(coverage.gap(0, Long.MAX_VALUE)).isNull();

        bypassing = context.getLogger("com.example.audit");
        own = new ListAppender<>();
        own.start();
        bypassing.addAppender(own);
        bypassing.setAdditive(false);
        coverage.onLevelChange(bypassing, Level.INFO);

        assertThat(coverage.bypassingLoggers()).contains("com.example.audit");
        assertThat(coverage.passiveGap(0, Long.MAX_VALUE)).contains("unassessed");

        bypassing.setLevel(Level.ERROR);
        coverage.onLevelChange(bypassing, Level.ERROR);
        assertThat(coverage.bypassingLoggers()).contains("com.example.audit");
        bypassing.setLevel(Level.OFF);
        coverage.onLevelChange(bypassing, Level.OFF);
        assertThat(coverage.bypassingLoggers()).isEmpty();
    }

    @Test
    void defaultMicrometerMetricsFilterDoesNotDisableExplicitCaughtOutcomeInspection() {
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        appender = RuntimeJournalLogAppender.install(event -> true);
        coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (LogbackMetrics metrics = new LogbackMetrics(java.util.List.of(), context)) {
            metrics.bindTo(registry);
            assertThat(context.getTurboFilterList())
                    .anyMatch(filter -> filter.getClass()
                            .getName()
                            .equals("io.micrometer.core.instrument.binder.logging.MetricsTurboFilter"));

            assertThat(coverage.gap(System.currentTimeMillis() + 1_000, Long.MAX_VALUE))
                    .as("the default native NEUTRAL metrics filter must not make all Caught outcomes unknown")
                    .isNull();
            TurboFilter actual = context.getTurboFilterList().stream()
                    .filter(filter -> filter.getClass()
                            .getName()
                            .equals("io.micrometer.core.instrument.binder.logging.MetricsTurboFilter"))
                    .findFirst()
                    .orElseThrow();
            TurboFilter observed = spy(actual);
            assertThat(observed.getClass()).isSameAs(actual.getClass());
            AtomicInteger callbacks = new AtomicInteger();
            doAnswer(invocation -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError(
                                "native NEUTRAL metrics policy must not run during coverage inspection");
                    })
                    .when(observed)
                    .decide(any(), any(), any(), any(), any(), any());
            int index = context.getTurboFilterList().indexOf(actual);
            context.getTurboFilterList().set(index, observed);
            try {
                assertThat(coverage.gap(System.currentTimeMillis() + 1_000, Long.MAX_VALUE))
                        .isNull();
                assertThat(coverage.passiveGap(System.currentTimeMillis() + 1_000, Long.MAX_VALUE))
                        .contains("unassessed");
                assertThat(callbacks.get()).isZero();
            } finally {
                context.getTurboFilterList().set(index, actual);
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void passiveMetadataReadsNeverInvokeTurboFiltersOrCustomAppenderNames() {
        appender = RuntimeJournalLogAppender.install(event -> true);
        coverage = (LogbackLogCoverage) LogbackLogCoverage.install();
        AtomicInteger callbacks = new AtomicInteger();
        TurboFilter filter = new TurboFilter() {
            @Override
            public FilterReply decide(
                    Marker marker, Logger logger, Level level, String format, Object[] parameters, Throwable failure) {
                callbacks.incrementAndGet();
                throw new AssertionError("log policy must not run while reading coverage");
            }
        };
        ListAppender<ILoggingEvent> custom = new ListAppender<>() {
            @Override
            public String getName() {
                callbacks.incrementAndGet();
                throw new AssertionError("application appender metadata must not be invoked");
            }
        };
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(custom);
        bypassing = context.getLogger("com.example.passive");
        own = new ListAppender<>();
        bypassing.addAppender(own);
        bypassing.setAdditive(false);
        filter.start();
        context.addTurboFilter(filter);
        try {
            assertThat(coverage.gap(0, Long.MAX_VALUE)).contains("filters");
            assertThat(coverage.bypassingLoggers()).contains("com.example.passive");
            assertThat(callbacks.get()).isZero();
        } finally {
            context.getTurboFilterList().remove(filter);
            filter.stop();
            root.detachAppender(custom);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4097})
    void additiveInventoryIsUnassessedWithoutTakingAnUnboundedEagerSnapshot(int inventorySize) {
        LoggerContext isolated = new LoggerContext();
        try {
            for (int i = 0; i < inventorySize; i++) {
                isolated.getLogger("normal.additive." + i);
            }
            AtomicInteger snapshots = new AtomicInteger();
            LoggerContext observed = spy(isolated);
            doAnswer(invocation -> {
                        snapshots.incrementAndGet();
                        return invocation.callRealMethod();
                    })
                    .when(observed)
                    .getLoggerList();
            LogbackLogCoverage local = new LogbackLogCoverage(observed);
            org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
            org.slf4j.bridge.SLF4JBridgeHandler.install();
            RuntimeJournalLogAppender nativeAppender = new RuntimeJournalLogAppender(event -> true);
            nativeAppender.setContext(isolated);
            nativeAppender.start();
            isolated.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(nativeAppender);
            String reason = local.passiveGap(0, Long.MAX_VALUE);

            assertAll(
                    () -> assertThat(snapshots.get()).isZero(),
                    () -> assertThat(reason).contains("unassessed"));
        } finally {
            isolated.stop();
        }
    }

    @Test
    void jdkLoggerInventoryIsNotClaimedCompleteWithoutBoundedPublicMetadata() {
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
        assertThat(coverage.passiveGap(System.currentTimeMillis() + 1_000, Long.MAX_VALUE))
                .contains("logger-inventory completeness is unassessed");
    }
}
