package io.github.jdubois.bootui.quarkus.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.ConnectionsPerRequest;
import io.github.jdubois.bootui.engine.insights.EventLoopBlocking;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RepeatedSelects;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BootUiHibernateStatementInspectorTests {

    @Test
    void actualPreparationOnlyCaptureDoesNotMakeExecutionOrConnectionAbsenceEligible() throws Exception {
        try (RuntimeJournal journal = journal()) {
            JournalAggregates aggregates = new JournalAggregates();
            journal.addListener(aggregates);
            SqlTraceRecorder recorder = recorder(journal);
            BootUiHibernateStatementInspector inspector = inspector(recorder);
            CorrelationContext request = CorrelationContext.forRequest("preparation-eligibility");
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
                inspector.inspect("select * from orders");
                for (int i = 0; i < 6; i++) {
                    inspector.inspect("select * from lines where order_id = ?");
                }
            }
            complete(journal, request);
            assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                    .isFalse();
            assertThat(journal.entries())
                    .filteredOn(entry -> entry.event().source() == JournalSource.SQL)
                    .hasSize(7)
                    .allSatisfy(entry -> assertThat(((SqlPayload) entry.event().payload()).provenance())
                            .isEqualTo(SqlPayload.Provenance.PREPARATION));
            RuntimeInsightsService service =
                    new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, List::of);
            service.setSqlCapture(() -> SqlCapture.of(recorder, false, false));

            assertThat(service.report().checks())
                    .filteredOn(check -> check.kind().equals(RepeatedSelects.KIND)
                            || check.kind().equals(ConnectionsPerRequest.KIND))
                    .hasSize(2)
                    .allSatisfy(check -> {
                        assertThat(check.eligibleRequests()).isZero();
                        assertThat(check.status()).isNotEqualTo("EVALUATED");
                    });
        }
    }

    @Test
    void preparationHonorsCapturePolicyAndStillPublishesWhenThePanelIsPaused() throws Exception {
        List<RuntimeEvent> published = new ArrayList<>();
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2_000, 200, 5);
        recorder.setRuntimeEventSink(published::add);
        recorder.setRecording(false);
        BootUiHibernateStatementInspector inspector = inspector(recorder);

        inspector.inspect("select * from orders");
        assertThat(recorder.recent()).isEmpty();
        assertThat(published)
                .singleElement()
                .satisfies(event -> assertThat(((SqlPayload) event.payload()).provenance())
                        .isEqualTo(SqlPayload.Provenance.PREPARATION));
        published.clear();
        BootUiJdbcCaptureGuard.runSuppressed(() -> inspector.inspect("select * from orders"));
        assertThat(published).isEmpty();

        SqlTraceRecorder disabled = new SqlTraceRecorder(false, true, false, false, 10, 100, 2_000, 200, 5);
        disabled.setRuntimeEventSink(published::add);
        assertThat(inspector(disabled).inspect("select 1")).isEqualTo("select 1");
        assertThat(published).isEmpty();
        assertThat(disabled.recent()).isEmpty();
    }

    @Test
    void inspectionWithoutExecutionOnAnEventLoopIsNotConfirmedBlocking() throws Exception {
        try (RuntimeJournal journal = journal()) {
            SqlTraceRecorder recorder = recorder(journal);
            BootUiHibernateStatementInspector inspector = inspector(recorder);
            CorrelationContext request = CorrelationContext.forRequest("preparation");
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
                assertThat(inspector.inspect("select * from orders")).isEqualTo("select * from orders");
            }
            complete(journal, request);

            assertThat(recorder.recent()).hasSize(1);
            assertThat(journal.entries())
                    .filteredOn(entry -> entry.event().source() == JournalSource.SQL)
                    .hasSize(1);
            assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, List::of)
                            .report()
                            .observations())
                    .noneMatch(observation -> observation.kind().equals(EventLoopBlocking.KIND));
        }
    }

    @Test
    void executedPreparedJdbcWithZeroDurationOnAnEventLoopStillCounts() throws Exception {
        try (RuntimeJournal journal = journal()) {
            SqlTraceRecorder recorder = recorder(journal);
            CorrelationContext request = CorrelationContext.forRequest("execution");
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
                recorder.recordNanos(
                        StatementType.PREPARED,
                        Category.SELECT,
                        "select * from orders",
                        List.of(),
                        0,
                        true,
                        null,
                        null,
                        0,
                        "connection",
                        Thread.currentThread().getName());
            }
            complete(journal, request);

            assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, List::of)
                            .report()
                            .observations())
                    .filteredOn(observation -> observation.kind().equals(EventLoopBlocking.KIND))
                    .singleElement()
                    .satisfies(observation -> {
                        assertThat(observation.status()).isEqualTo("OBSERVED");
                        assertThat(observation.sentence()).contains("1 JDBC statement");
                    });
        }
    }

    private static RuntimeJournal journal() {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
    }

    private static SqlTraceRecorder recorder(RuntimeJournal journal) {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2_000, 200, 5);
        recorder.setRuntimeEventSink(journal);
        recorder.setThreadKindClassifier(() -> ThreadKind.EVENT_LOOP);
        return recorder;
    }

    @SuppressWarnings("unchecked")
    private static BootUiHibernateStatementInspector inspector(SqlTraceRecorder recorder) {
        Instance<SqlTraceRecorder> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(recorder);
        return new BootUiHibernateStatementInspector(instance);
    }

    private static void complete(RuntimeJournal journal, CorrelationContext request) throws InterruptedException {
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                System.currentTimeMillis(),
                1_000_000,
                request,
                Thread.currentThread().getName(),
                null,
                false,
                new HttpPayload("GET", "/orders", "/orders", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }
}
