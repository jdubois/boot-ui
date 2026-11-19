package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeInsightsCaptureCounterTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final JournalAggregates aggregates = new JournalAggregates();
    private final SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 20, 100, 2_000, 200, 5);
    private int requests;

    RuntimeInsightsCaptureCounterTests() {
        journal.addListener(aggregates);
        recorder.setRuntimeEventSink(journal);
    }

    @AfterEach
    void close() {
        journal.close();
    }

    @ParameterizedTest
    @EnumSource(
            value = SqlPayload.Provenance.class,
            names = {"PREPARATION", "UNKNOWN"})
    void nonExecutionSqlDoesNotMakeExecutionOrConnectionAbsenceEligible(SqlPayload.Provenance provenance)
            throws InterruptedException {
        recorder.registerCaptureSource("db", provenance);
        request("/orders", repeatedSql(provenance, false));
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isFalse();

        RuntimeInsightsReportDto report = service().report();

        assertAll(
                () -> assertThat(check(report, RepeatedSelects.KIND).eligibleRequests())
                        .as("preparation/unknown SQL is not an observed absence of repeated executions")
                        .isZero(),
                () -> assertThat(check(report, ConnectionsPerRequest.KIND).eligibleRequests())
                        .as("no logical-connection feeder or event was established")
                        .isZero(),
                () -> assertThat(check(report, RepeatedSelects.KIND).status()).isNotEqualTo("EVALUATED"),
                () -> assertThat(check(report, ConnectionsPerRequest.KIND).status())
                        .isNotEqualTo("EVALUATED"));
    }

    @Test
    void zeroDurationExecutionStillProvesRepeatedSelectsAndOverlappingConnections() throws InterruptedException {
        SqlTracingProxies.wrapNamed(mock(DataSource.class), recorder, "db");
        for (int i = 0; i < 3; i++) {
            request("/orders", repeatedSql(SqlPayload.Provenance.EXECUTION, true));
        }

        RuntimeInsightsReportDto report = service().report();

        assertThat(observations(report, RepeatedSelects.KIND)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("OBSERVED");
            assertThat(row.affected()).isEqualTo(3);
            assertThat(row.limitations()).contains(RepeatedSelects.UNMEASURED_REPEAT_TIME);
        });
        assertThat(observations(report, ConnectionsPerRequest.KIND))
                .singleElement()
                .satisfies(row -> assertThat(row.affected()).isEqualTo(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {RepeatedSelects.KIND, ConnectionsPerRequest.KIND})
    void aCurrentSqlSupplierFailureDoesNotEraseKnownRetainedFindings(String kind) throws InterruptedException {
        SqlTracingProxies.wrapNamed(mock(DataSource.class), recorder, "db");
        for (int i = 0; i < 3; i++) {
            request("/orders", repeatedSql(SqlPayload.Provenance.EXECUTION, true));
        }
        AtomicBoolean unavailable = new AtomicBoolean();
        RuntimeInsightsService service = service();
        service.setSqlCapture(() -> {
            if (unavailable.get()) {
                throw new IllegalStateException("controlled current capture-status failure");
            }
            return SqlCapture.capturing();
        });
        List<String> retained = observations(service.report(), kind).stream()
                .map(RuntimeObservationDto::id)
                .toList();
        assertThat(retained).hasSize(1);
        long sequence = journal.status().lastSequence();

        unavailable.set(true);
        RuntimeInsightsReportDto after = service.report();

        assertThat(journal.status().lastSequence()).isEqualTo(sequence);
        assertThat(observations(after, kind))
                .as("current capture uncertainty is not a panel-visibility revocation")
                .extracting(RuntimeObservationDto::id)
                .containsExactlyElementsOf(retained);
        assertThat(service.insight(retained.get(0)).available()).isTrue();
        assertThat(check(after, kind).reason()).contains("Retained SQL and connection evidence is still counted");
        assertThat(observations(after, kind).get(0).limitations())
                .anyMatch(reason -> reason.contains("Retained SQL and connection evidence is still counted"));
    }

    @Test
    void zeroStatementConnectionsQualifyOnlyTheirDeclaredScopeAndChangesRefreshWithoutNewEvents()
            throws InterruptedException {
        SqlTracingProxies.wrapNamed(mock(DataSource.class), recorder, "db");
        request("/zero", List.of(new Child(JournalSource.CONNECTION, 1_000, new ConnectionPayload("db", 0, 0, 1))));
        request("/unobserved", List.of());
        request(
                "/unknown-source",
                List.of(new Child(JournalSource.CONNECTION, 1_000, new ConnectionPayload(null, 0, 0, 1))));
        RuntimeInsightsService service = service();
        assertThat(check(service.report(), RepeatedSelects.KIND).eligibleRequests())
                .isEqualTo(1);
        assertThat(check(service.report(), ConnectionsPerRequest.KIND).eligibleRequests())
                .isEqualTo(1);
        long sequence = journal.status().lastSequence();
        recorder.registerCaptureSource("db", SqlPayload.Provenance.PREPARATION);
        assertThat(journal.status().lastSequence()).isEqualTo(sequence);
        assertThat(check(service.report(), RepeatedSelects.KIND).eligibleRequests())
                .isZero();
        assertThat(check(service.report(), ConnectionsPerRequest.KIND).eligibleRequests())
                .isEqualTo(1);
    }

    @Test
    void connectionFindingDenominatorsBelongToTheirObservedDataSource() throws InterruptedException {
        request("/pools", repeatedSql(SqlPayload.Provenance.EXECUTION, true));
        request("/pools", List.of(new Child(JournalSource.CONNECTION, 1_000, new ConnectionPayload("audit", 0, 1, 1))));
        request("/pools", List.of());
        RuntimeInsightsReportDto report = service().report();
        assertThat(check(report, ConnectionsPerRequest.KIND).eligibleRequests()).isEqualTo(2);
        assertThat(observations(report, ConnectionsPerRequest.KIND))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eligible()).isEqualTo(1);
                    assertThat(row.affected()).isEqualTo(1);
                });
    }

    @Test
    void hiddenLogsDoNotResolveCaptureMetadata() {
        AtomicBoolean called = new AtomicBoolean();
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals("log-tail"), InsightsStack.QUARKUS, List::of);
        service.setLogCoverage(() -> {
            called.set(true);
            return LogCoverage.UNREADABLE;
        });
        assertThat(check(service.report(), FrameworkWarningsByRoute.KIND).status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(called.get()).isFalse();
    }

    @Test
    void aLiveLogGapRefreshesEligibilityAndQualifiesIndependentErrorsWithoutDroppingFacts()
            throws InterruptedException {
        request("/zero", List.of());
        request(
                "/failed",
                List.of(new Child(
                        JournalSource.EXCEPTION,
                        0,
                        new ExceptionPayload("gap", "java.lang.IllegalStateException", "failure"))));
        AtomicReference<String> gap = new AtomicReference<>();
        LogCoverage coverage = new LogCoverage() {
            @Override
            public String gap(long from, long to) {
                return gap.get();
            }

            @Override
            public List<String> bypassingLoggers() {
                return List.of();
            }
        };
        RuntimeInsightsService service = service();
        service.setLogCoverage(() -> coverage);
        assertThat(check(service.report(), FrameworkWarningsByRoute.KIND).eligibleRequests())
                .isEqualTo(2);
        long sequence = journal.status().lastSequence();
        gap.set("controlled detached handler");
        RuntimeInsightsReportDto after = service.report();
        assertThat(journal.status().lastSequence()).isEqualTo(sequence);
        assertThat(check(after, FrameworkWarningsByRoute.KIND).eligibleRequests())
                .isZero();
        assertThat(check(after, FrameworkWarningsByRoute.KIND).status()).isEqualTo("INSUFFICIENT");
        assertThat(check(after, ErrorsBehind2xx.KIND).reason()).contains("controlled detached handler");
        assertThat(observations(after, ErrorsBehind2xx.KIND))
                .singleElement()
                .satisfies(row -> assertThat(row.limitations())
                        .anyMatch(reason -> reason.contains("controlled detached handler")));
    }

    @Test
    void preparationOnlyRequestsDoNotPadKnownExecutionOrConnectionDenominators() throws InterruptedException {
        SqlTracingProxies.wrapNamed(mock(DataSource.class), recorder, "db");
        for (int i = 0; i < 3; i++) {
            request("/orders", repeatedSql(SqlPayload.Provenance.EXECUTION, true));
        }
        recorder.registerCaptureSource("db", SqlPayload.Provenance.PREPARATION);
        request("/orders", repeatedSql(SqlPayload.Provenance.PREPARATION, false));
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isFalse();

        RuntimeInsightsReportDto report = service().report();

        assertAll(
                () -> assertThat(observations(report, RepeatedSelects.KIND))
                        .singleElement()
                        .satisfies(row -> assertThat(row.eligible()).isEqualTo(3)),
                () -> assertThat(observations(report, ConnectionsPerRequest.KIND))
                        .singleElement()
                        .satisfies(row -> assertThat(row.eligible()).isEqualTo(3)),
                () -> assertThat(check(report, RepeatedSelects.KIND).eligibleRequests())
                        .isEqualTo(3),
                () -> assertThat(check(report, ConnectionsPerRequest.KIND).eligibleRequests())
                        .isEqualTo(3));
    }

    @Test
    void anActualNativeExecutionFeederQualifiesZeroButMixedPreparationOrUnknownScopeDoesNot()
            throws InterruptedException {
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isFalse();
        SqlTracingProxies.wrapNamed(mock(DataSource.class), recorder, "db");
        assertThat(journal.entries()).isEmpty();
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isTrue();

        recorder.registerCaptureSource("db", SqlPayload.Provenance.PREPARATION);
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isFalse();
        recorder.registerCaptureSource(null, SqlPayload.Provenance.UNKNOWN);
        assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                .isFalse();

        request("/orders", repeatedSql(SqlPayload.Provenance.EXECUTION, true));
        assertThat(observations(service().report(), RepeatedSelects.KIND)).hasSize(1);
        assertThat(observations(service().report(), ConnectionsPerRequest.KIND)).hasSize(1);
    }

    @Test
    void selectedAndVisibleLogsWithoutActualCaptureDoNotEstablishHealthyAbsence() throws InterruptedException {
        assertThat(LogCoverage.UNREADABLE.gap(1_000, 1_030)).isNotNull();
        request("/orders", List.of());
        assertThat(journal.records(JournalSource.LOG)).isTrue();

        RuntimeInsightCheckDto check = check(service().report(), FrameworkWarningsByRoute.KIND);

        assertAll(
                () -> assertThat(check.eligibleRequests())
                        .as("selection and visibility alone are not a log-capture proof")
                        .isZero(),
                () -> assertThat(check.status()).isNotEqualTo("EVALUATED"));
    }

    @Test
    void capturedRoutedAndUnownedErrorsAndIndependent2xxFailuresRemainFacts() throws InterruptedException {
        assertThat(LogCoverage.UNREADABLE.gap(1_000, 1_030)).isNotNull();
        request(
                "/orders",
                List.of(new Child(
                        JournalSource.LOG,
                        0,
                        new LogPayload("org.hibernate.query", "ERROR", "Captured framework failure", null))));
        request(
                "/independent",
                List.of(new Child(
                        JournalSource.EXCEPTION,
                        0,
                        new ExceptionPayload("controlled", "java.lang.IllegalStateException", "failure"))));
        journal.offer(RuntimeEvent.of(
                JournalSource.LOG,
                2_000,
                0,
                null,
                "startup",
                null,
                true,
                new LogPayload("org.springframework.context", "ERROR", "Captured startup failure", null)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeInsightsReportDto report = service().report();

        assertThat(observations(report, FrameworkWarningsByRoute.KIND))
                .extracting(RuntimeObservationDto::subject)
                .containsExactlyInAnyOrder("GET /orders", FrameworkWarningsByRoute.NO_REQUEST);
        assertThat(observations(report, ErrorsBehind2xx.KIND))
                .extracting(RuntimeObservationDto::subject)
                .contains("GET /orders", "GET /independent");
    }

    @Test
    void unknownStatementsQualifyButNeverEraseKnownRepeatedExecutions() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            List<Child> children = new ArrayList<>(repeatedSql(SqlPayload.Provenance.EXECUTION, false));
            children.addAll(repeatedSql(SqlPayload.Provenance.UNKNOWN, false));
            request("/mixed", children);
        }
        RuntimeInsightsReportDto report = service().report();
        assertThat(check(report, RepeatedSelects.KIND).reason()).contains("unknown SQL events");
        assertThat(observations(report, RepeatedSelects.KIND)).singleElement().satisfies(row -> {
            assertThat(row.affected()).isEqualTo(3);
            assertThat(row.eligible()).isEqualTo(3);
            assertThat(row.limitations())
                    .contains("Preparation-only or unknown SQL in these requests is not counted as executions.");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coverageStartsAtTheEarliestRetainedWorkNotTheFirstSequence(boolean unownedError) throws InterruptedException {
        CorrelationContext context = CorrelationContext.forRequest("child-before-completion");
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_200,
                0,
                context,
                "http-counter",
                null,
                false,
                new SqlPayload("select 1", null, "db", false)));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                300_000_000,
                context,
                "http-counter",
                null,
                false,
                new HttpPayload("GET", "/ordered", "/ordered", null, 200)));
        if (unownedError) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.LOG,
                    900,
                    0,
                    null,
                    "startup",
                    null,
                    false,
                    new LogPayload("org.springframework.context", "ERROR", "controlled unowned error", null)));
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(journal.status().oldestRetainedEpochMillis()).isEqualTo(1_200);
        List<Long> observedStarts = new ArrayList<>();
        LogCoverage coverage = new LogCoverage() {
            @Override
            public String gap(long from, long to) {
                observedStarts.add(from);
                return from <= 1_100 ? "controlled capture gap at T1" : null;
            }

            @Override
            public List<String> bypassingLoggers() {
                return List.of();
            }
        };
        RuntimeInsightsService service = service();
        service.setLogCoverage(() -> coverage);

        RuntimeInsightsReportDto report = service.report();
        service.report();

        assertAll(
                () -> assertThat(check(report, FrameworkWarningsByRoute.KIND).eligibleRequests())
                        .isZero(),
                () -> assertThat(check(report, FrameworkWarningsByRoute.KIND).reason())
                        .contains("controlled capture gap at T1"),
                () -> assertThat(observedStarts)
                        .hasSizeGreaterThanOrEqualTo(3)
                        .contains(1_000L)
                        .allMatch(start -> start == 1_000L || (unownedError && start == 900L)));
        if (unownedError) {
            assertThat(observedStarts).contains(900L);
            assertThat(observations(report, FrameworkWarningsByRoute.KIND))
                    .singleElement()
                    .satisfies(row -> assertThat(row.limitations())
                            .anyMatch(reason -> reason.contains("controlled capture gap at T1")));
        }
    }

    @Test
    void incompleteLogCaptureDescribesFactsRatherThanARequestWideFrequency() throws InterruptedException {
        request(
                "/scope",
                List.of(new Child(
                        JournalSource.LOG,
                        0,
                        new LogPayload("org.hibernate.query", "WARN", "controlled captured warning", null))));
        request("/scope", List.of());
        request(
                "/scope",
                List.of(new Child(
                        JournalSource.LOG,
                        0,
                        new LogPayload("org.hibernate.query", "WARN", "controlled captured warning", null))));
        RuntimeInsightsReportDto report = service().report();
        assertThat(check(report, FrameworkWarningsByRoute.KIND).eligibleRequests())
                .isEqualTo(2);
        assertThat(check(report, FrameworkWarningsByRoute.KIND).status()).isEqualTo("EVALUATED");
        assertThat(observations(report, FrameworkWarningsByRoute.KIND))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eligible()).isZero();
                    assertThat(row.affected()).isEqualTo(2);
                    assertThat(row.status()).isEqualTo("OBSERVED");
                    assertThat(row.sentence()).containsIgnoringCase("captured").doesNotContain("2 of 2", "2 of 3");
                    assertThat(RuntimeInsightsAgentView.compact(row).eligible()).isZero();
                    assertThat(RuntimeInsightsAgentView.compact(row).affected()).isEqualTo(2);
                });
    }

    @Test
    void logQualificationReasonsAreNormalizedAndSeparatedBeforeTheRetainedFactNote() throws InterruptedException {
        request("/reason", List.of());
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                2_000,
                0,
                CorrelationContext.forRequest("reason-beta"),
                "http-reason",
                null,
                false,
                new HttpPayload("GET", "/reason", "/reason", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        RuntimeInsightsService service = service();
        service.setLogCoverage(() -> new LogCoverage() {
            @Override
            public String gap(long from, long to) {
                return from == 1_000 ? "  alpha capture reason " : " beta capture reason. ";
            }

            @Override
            public List<String> bypassingLoggers() {
                return List.of();
            }
        });

        assertThat(check(service.report(), FrameworkWarningsByRoute.KIND).reason())
                .contains("Log capture is not proven complete: alpha capture reason. beta capture reason."
                        + " Retained log events are still counted")
                .doesNotContain("incomplete log coverage", "..", "reason Retained");
    }

    @Test
    void hiddenUnitsDoNotResolveCoverageForTheirWindows() throws InterruptedException {
        request("/hidden", List.of());
        AtomicBoolean called = new AtomicBoolean();
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals("http-exchanges"), InsightsStack.QUARKUS, List::of);
        service.setLogCoverage(() -> {
            called.set(true);
            return LogCoverage.UNREADABLE;
        });
        assertThat(service.report().window().requests()).isZero();
        assertThat(called.get()).isFalse();
    }

    @Test
    void passiveProjectionNeverCallsTheExistingExplicitDeepInspection() throws InterruptedException {
        request("/passive", List.of());
        RuntimeInsightsService service = service();
        service.setLogCoverage(() -> new LogCoverage() {
            @Override
            public String gap(long from, long to) {
                throw new AssertionError("passive projection must not invoke explicit deep inspection");
            }

            @Override
            public String passiveGap(long from, long to) {
                return null;
            }

            @Override
            public List<String> bypassingLoggers() {
                throw new AssertionError("passive projection must not enumerate the deep logger inventory");
            }
        });
        assertThat(check(service.report(), FrameworkWarningsByRoute.KIND).eligibleRequests())
                .isEqualTo(1);
        assertThat(check(service.report(), FrameworkWarningsByRoute.KIND).status())
                .isEqualTo("EVALUATED");
    }

    private RuntimeInsightsService service() {
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, panel -> true, InsightsStack.QUARKUS, List::of);
        service.setSqlCapture(() -> SqlCapture.of(recorder, false, false));
        service.assumeValidated();
        return service;
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report, String kind) {
        return report.checks().stream()
                .filter(row -> row.kind().equals(kind))
                .findFirst()
                .orElseThrow();
    }

    private static List<RuntimeObservationDto> observations(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(row -> row.kind().equals(kind))
                .toList();
    }

    private static List<Child> repeatedSql(SqlPayload.Provenance provenance, boolean connections) {
        List<Child> children = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            children.add(new Child(
                    JournalSource.SQL,
                    0,
                    new SqlPayload(
                            i == 0 ? "select * from orders" : "select * from lines where order_id = ?",
                            "OrderService.load:10",
                            "db",
                            false,
                            null,
                            null,
                            100 + i,
                            0,
                            provenance,
                            1,
                            null)));
        }
        if (connections) {
            children.add(new Child(JournalSource.CONNECTION, 30_000_000, new ConnectionPayload("db", 0, 7, 0)));
            children.add(new Child(JournalSource.CONNECTION, 5_000_000, new ConnectionPayload("db", 0, 1, 1_000)));
        }
        return children;
    }

    private void request(String route, List<Child> children) throws InterruptedException {
        CorrelationContext context = CorrelationContext.forRequest("capture-counter-" + ++requests);
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-counter", null, false, child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                30_000_000,
                context,
                "http-counter",
                null,
                false,
                new HttpPayload("GET", route, route, null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
