package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import ch.qos.logback.classic.LoggerContext;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.insights.RuntimeInsightsController;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.insights.FrameworkWarningsByRoute;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

class RuntimeInsightsLogCaptureCounterTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNativeMissingJournalAppenderDoesNotBecomeHealthyAbsenceOnEitherSpringStack(boolean reactive)
            throws InterruptedException {
        try (RuntimeJournal journal = new RuntimeJournal(
                        new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                        RunIdentity.start());
                GenericApplicationContext application =
                        reactive ? new GenericReactiveWebApplicationContext() : new GenericApplicationContext()) {
            LoggerContext logging = new LoggerContext();
            try {
                LogCoverage coverage = new LogbackLogCoverage(logging);
                assertThat(coverage.gap(1_000, 1_030)).contains("not attached");
                AtomicInteger lazyFactories = new AtomicInteger();
                application.getBeanFactory().registerSingleton("bootUiLogCoverage", coverage);
                application.getBeanFactory().registerSingleton("bootUiRuntimeJournal", journal);
                application.registerBean(
                        "lazyApplicationLogCoverage",
                        LogCoverage.class,
                        () -> {
                            lazyFactories.incrementAndGet();
                            throw new AssertionError("a passive report must not create application coverage");
                        },
                        definition -> definition.setLazyInit(true));
                application.refresh();
                BootUiProperties properties = new BootUiProperties();
                RuntimeInsightsController controller = new RuntimeInsightsController(
                        application,
                        properties,
                        application.getBeanProvider(RuntimeJournal.class),
                        application.getBeanProvider(JournalAggregates.class),
                        application.getBeanProvider(MappingProvider.class),
                        application.getBeanProvider(BeanProvider.class));
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        1_000,
                        30_000_000,
                        CorrelationContext.forRequest("native-log-counter"),
                        "http-counter",
                        null,
                        false,
                        new HttpPayload("GET", "/orders", "/orders", null, 200)));
                assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
                long sequence = journal.status().lastSequence();

                RuntimeInsightCheckDto check = controller.report().checks().stream()
                        .filter(row -> row.kind().equals(FrameworkWarningsByRoute.KIND))
                        .findFirst()
                        .orElseThrow();

                assertAll(
                        () -> assertThat(check.eligibleRequests()).isZero(),
                        () -> assertThat(check.status()).isNotEqualTo("EVALUATED"),
                        () -> assertThat(check.reason()).contains("not attached"),
                        () -> assertThat(lazyFactories.get()).isZero(),
                        () -> assertThat(coverage.gap(1_000, 1_030)).contains("not attached"),
                        () -> assertThat(journal.status().lastSequence()).isEqualTo(sequence));
            } finally {
                logging.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lazyAndCustomCoverageNeverRunApplicationFactoriesOrCallbacks(boolean reactive) {
        try (RuntimeJournal journal = new RuntimeJournal(
                        new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                        RunIdentity.start());
                GenericApplicationContext application =
                        reactive ? new GenericReactiveWebApplicationContext() : new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            LogCoverage custom = new LogCoverage() {
                @Override
                public String gap(long from, long to) {
                    callbacks.incrementAndGet();
                    throw new AssertionError("an application coverage callback must not run");
                }

                @Override
                public java.util.List<String> bypassingLoggers() {
                    throw new AssertionError("application logging policy must not run");
                }
            };
            application.getBeanFactory().registerSingleton("bootUiRuntimeJournal", journal);
            application.registerBean(
                    "bootUiLogCoverage",
                    LogCoverage.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("a lazy application factory must not run");
                    },
                    definition -> definition.setLazyInit(true));
            application.refresh();
            RuntimeInsightsController controller = new RuntimeInsightsController(
                    application,
                    new BootUiProperties(),
                    application.getBeanProvider(RuntimeJournal.class),
                    application.getBeanProvider(JournalAggregates.class),
                    application.getBeanProvider(MappingProvider.class),
                    application.getBeanProvider(BeanProvider.class));
            controller.report();
            application.getBeanFactory().registerSingleton("bootUiLogCoverage", custom);
            controller.report();
            assertThat(callbacks.get()).isZero();
        }
    }
}
