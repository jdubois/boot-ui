package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.mcp.McpControlAcks;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SqlTraceRecorderTests {

    @Test
    void profileQualificationFollowsCaptureDeclarationsNotEvictableRows() {
        SqlTraceRecorder recorder = recorder(true, false, 1, 100);
        assertThat(recorder.executionCaptureLimitation()).isNull();
        recorder.registerCaptureSource("orm", SqlPayload.Provenance.PREPARATION);
        assertThat(recorder.executionCaptureLimitation()).contains("preparation", "not execution evidence");
        recorder.recordPreparation("select * from orders", "orm");
        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.executionCaptureLimitation()).contains("preparation", "execution timing");
        recorder.registerCaptureSource(null, SqlPayload.Provenance.UNKNOWN);
        assertThat(recorder.executionCaptureLimitation()).contains("unknown", "unverified");
    }

    @Test
    void preparationIsRetainedButDoesNotManufactureExecutionsOrStatistics() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        recorder.registerDataSource("db");
        for (int i = 0; i < 6; i++) {
            recorder.recordPreparation("select * from orders", "db");
        }
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
                "c1",
                "main");

        assertThat(recorder.report(false).entries()).hasSize(7);
        assertThat(recorder.entries(false)).hasSize(1);
        assertThat(recorder.stats().totalQueries()).isEqualTo(1);
        assertThat(recorder.topStatements()).singleElement().satisfies(group -> {
            assertThat(group.executions()).isEqualTo(1);
            assertThat(group.potentialNPlusOne()).isFalse();
        });
        assertThat(recorder.report(false).warnings())
                .anyMatch(warning -> warning.contains("6 SQL capture(s) observed preparation only"));
        assertThat(new SqlTraceInsightsService(recorder)
                        .insights(List.of(), java.util.Set.of(), RouteTemplateResolver.empty())
                        .notes())
                .anyMatch(note -> note.contains("excluded from rankings"));
        assertThat(published).hasSize(7);
        assertThat(published.subList(0, 6))
                .allSatisfy(event -> assertThat(((SqlPayload) event.payload()).provenance())
                        .isEqualTo(SqlPayload.Provenance.PREPARATION));
        assertThat(((SqlPayload) published.get(6).payload()).executed()).isTrue();
    }

    private SqlTraceRecorder recorder(boolean enabled, boolean captureParameters, int maxEntries, long slowMillis) {
        return recorder(enabled, captureParameters, false, maxEntries, slowMillis);
    }

    private SqlTraceRecorder recorder(
            boolean enabled, boolean captureParameters, boolean captureCallSite, int maxEntries, long slowMillis) {
        return new SqlTraceRecorder(
                enabled, true, captureParameters, captureCallSite, maxEntries, slowMillis, 2000, 200, 5);
    }

    private void record(SqlTraceRecorder recorder, Category category, String sql, int batchSize) {
        recorder.record(
                StatementType.STATEMENT, category, sql, List.of(), 1, true, null, null, batchSize, "c1", "main");
    }

    @Test
    void publishesEachRecordedStatementToTheJournalWithItsCorrelationButNoBindValues() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        CorrelationContext request =
                CorrelationContext.forRequest("0123456789abcdef").withDataSource("orders");

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            recorder.record(
                    StatementType.PREPARED,
                    Category.SELECT,
                    "select * from orders where id = ?",
                    List.of("42"),
                    150_000,
                    false,
                    "boom",
                    null,
                    0,
                    "c1",
                    "http-nio-8080-exec-1");
        }
        BootUiCorrelation.replace(CorrelationContext.NONE);
        record(recorder, Category.SELECT, "select 1", 0);

        assertThat(published).hasSize(2);
        RuntimeEvent event = published.get(0);
        assertThat(event.source()).isEqualTo(JournalSource.SQL);
        assertThat(event.requestId()).isEqualTo("0123456789abcdef");
        assertThat(event.durationNanos()).isEqualTo(150_000_000L);
        assertThat(event.failedOrSlow()).isTrue();
        assertThat(event.thread()).isEqualTo("http-nio-8080-exec-1");
        SqlPayload payload = (SqlPayload) event.payload();
        assertThat(payload)
                .usingRecursiveComparison()
                .ignoringFields("completedNanos")
                .isEqualTo(new SqlPayload("select * from orders where id = ?", null, "orders", true));
        assertThat(payload.completedNanos()).isPositive();
        assertThat(published.get(1).requestId()).isNull();
    }

    @Test
    void publishesTheRequestPhaseAndMonotonicCompletionOfEachStatement() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        RequestPhases phases = new RequestPhases();
        recorder.setRequestPhases(phases);
        phases.begin("0123456789abcdef");
        long before = System.nanoTime();

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            phases.mark("0123456789abcdef", RequestPhase.HANDLER);
            record(recorder, Category.SELECT, "select * from orders", 0);
            phases.mark("0123456789abcdef", RequestPhase.RESPONSE);
            record(recorder, Category.SELECT, "select * from lines", 0);
        }

        SqlPayload handler = (SqlPayload) published.get(0).payload();
        SqlPayload response = (SqlPayload) published.get(1).payload();
        assertThat(handler.phase()).isEqualTo(RequestPhase.HANDLER);
        assertThat(response.phase()).isEqualTo(RequestPhase.RESPONSE);
        assertThat(handler.completedNanos()).isGreaterThanOrEqualTo(before);
        assertThat(response.completedNanos()).isGreaterThanOrEqualTo(handler.completedNanos());
    }

    @Test
    void stampsNoRequestPhaseOnAStatementOfAPropagatedTask() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 8, 100, 2000, 200, 5, 50);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        RequestPhases phases = new RequestPhases();
        recorder.setRequestPhases(phases);
        phases.begin("0123456789abcdef");
        phases.mark("0123456789abcdef", RequestPhase.RESPONSE);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(
                CorrelationContext.forRequest("0123456789abcdef").withExecutionId(ExecutionIds.nextAsync()))) {
            record(recorder, Category.UPDATE, "update audit set seen = 1", 0);
        }

        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("0123456789abcdef");
            assertThat(((SqlPayload) event.payload()).phase()).isNull();
        });
    }

    @Test
    void publishesNothingWhatTheBufferDoesNotRecord() {
        SqlTraceRecorder recorder = recorder(false, false, 10, 100);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);

        record(recorder, Category.SELECT, "select 1", 0);

        assertThat(published).isEmpty();
    }

    @Test
    void recordsNothingWhenDisabled() {
        SqlTraceRecorder recorder = recorder(false, false, 10, 100);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void recordsNothingWhenPaused() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setRecording(false);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).isEmpty();

        recorder.setRecording(true);
        record(recorder, Category.SELECT, "select 2", 0);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void recordsNothingWhenCaptureGuardSuppressed() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard.runSuppressed(
                () -> record(recorder, Category.SELECT, "select 1", 0));
        assertThat(recorder.recent()).isEmpty();

        // Suppression is scoped to the block only; recording resumes normally afterward.
        record(recorder, Category.SELECT, "select 2", 0);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void suspendForIdleClearsAndStopsRecordingUntilResumed() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).hasSize(1);

        recorder.suspendForIdle();
        assertThat(recorder.recent()).isEmpty();
        record(recorder, Category.SELECT, "select 2", 0);
        assertThat(recorder.recent()).isEmpty();

        recorder.resumeFromIdle();
        record(recorder, Category.SELECT, "select 3", 0);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void resumeFromIdleDoesNotOverrideUserPause() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setRecording(false);

        recorder.suspendForIdle();
        recorder.resumeFromIdle();

        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void evictsOldestBeyondCapacityAndCountsEvictions() {
        SqlTraceRecorder recorder = recorder(true, false, 2, 100);
        record(recorder, Category.SELECT, "first", 0);
        record(recorder, Category.SELECT, "second", 0);
        record(recorder, Category.SELECT, "third", 0);

        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::sql)
                .containsExactly("third", "second");
        assertThat(recorder.totalCaptured()).isEqualTo(3);
        assertThat(recorder.evicted()).isEqualTo(1);
    }

    private void recordTimed(SqlTraceRecorder recorder, String sql, long durationMicros, boolean success) {
        recorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                sql,
                List.of(),
                durationMicros,
                success,
                success ? null : "boom",
                null,
                0,
                "c1",
                "main");
    }

    @Test
    void floodOfFastQueriesKeepsRecentFailedAndSlowExecutionsUpToTheReservedShare() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5, 50);
        recordTimed(recorder, "failed-1", 1, false);
        recordTimed(recorder, "slow-1", 100_000, true);
        recordTimed(recorder, "failed-2", 1, false);
        recordTimed(recorder, "slow-2", 250_000, true);
        recordTimed(recorder, "failed-3", 1, false);
        for (int i = 0; i < 500; i++) {
            recordTimed(recorder, "fast-" + i, 99_999, true);
        }

        assertThat(recorder.getReservedCapacity()).isEqualTo(4);
        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::sql)
                .containsExactly(
                        "fast-499", "fast-498", "fast-497", "fast-496", "failed-3", "slow-2", "failed-2", "slow-1");
        assertThat(recorder.retention()).isEqualTo(new CaptureRetentionDto(false, 8, 4, 8, 4, 497L, 100L));
    }

    @Test
    void disabledSlowThresholdReservesOnlyFailures() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 4, 0, 2000, 200, 5, 50);
        recordTimed(recorder, "failed", 1, false);
        recordTimed(recorder, "very-slow", 60_000_000, true);
        for (int i = 0; i < 10; i++) {
            recordTimed(recorder, "fast-" + i, 1, true);
        }

        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::sql)
                .containsExactly("fast-9", "fast-8", "fast-7", "failed");
        assertThat(recorder.retention().slowThresholdMillis()).isZero();
        assertThat(recorder.retention().reserved()).isEqualTo(1);
    }

    @Test
    void reportRetentionReconcilesWithEntriesStatsAndWarning() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 4, 100, 2000, 200, 5, 25);
        recordTimed(recorder, "failed", 1, false);
        for (int i = 0; i < 6; i++) {
            recordTimed(recorder, "fast-" + i, 1, true);
        }

        SqlTraceReport report = recorder.report(false);

        CaptureRetentionDto retention = report.retention();
        assertThat(retention.retained()).isEqualTo(report.entries().size()).isEqualTo(4);
        assertThat(retention.capacity()).isEqualTo(report.bufferSize());
        assertThat(retention.evicted()).isEqualTo(report.stats().evicted()).isEqualTo(3L);
        assertThat(retention.retained() + retention.evicted()).isEqualTo(report.totalCaptured());
        assertThat(retention.reserved()).isEqualTo(1);
        assertThat(report.stats().failedQueries()).isEqualTo(1);
        assertThat(report.warnings())
                .contains("Older queries were dropped; the buffer keeps up to 4 executions, reserving 1 for the most "
                        + "recent failed or slow ones, so routine executions are dropped first.");
    }

    @Test
    void zeroReservedShareKeepsTheStrictlyNewestWarning() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 2, 100, 2000, 200, 5, 0);
        recordTimed(recorder, "failed", 1, false);
        recordTimed(recorder, "fast-1", 1, true);
        recordTimed(recorder, "fast-2", 1, true);

        SqlTraceReport report = recorder.report(false);
        assertThat(report.entries()).extracting(SqlTraceEntryDto::sql).containsExactly("fast-2", "fast-1");
        assertThat(report.retention().reservedCapacity()).isZero();
        assertThat(report.warnings())
                .contains("Older queries were dropped; the buffer keeps up to 2 executions, newest first.");
    }

    @Test
    void unavailableReportCarriesNoRetention() {
        assertThat(SqlTraceReport.unavailable("off").retention()).isNull();
    }

    @Test
    void dropsParametersWhenCaptureDisabled() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select ?",
                List.of("'x'"),
                1,
                true,
                null,
                null,
                0,
                "c1",
                "main");
        assertThat(recorder.recent().get(0).parameters()).isEmpty();
    }

    @Test
    void keepsParametersAndThreadWhenCaptureEnabled() {
        SqlTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select ?",
                List.of("'x'"),
                1,
                true,
                null,
                null,
                0,
                "c1",
                "worker-1");
        assertThat(recorder.recent().get(0).parameters()).containsExactly("'x'");
        assertThat(recorder.recent().get(0).thread()).isEqualTo("worker-1");
    }

    @Test
    void flagsSlowQueriesByThreshold() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        // The threshold stays configured in milliseconds; the comparison is in microseconds.
        assertThat(recorder.isSlow(150_000)).isTrue();
        assertThat(recorder.isSlow(100_000)).isTrue();
        assertThat(recorder.isSlow(50_000)).isFalse();
        assertThat(recorder.isSlow(900)).isFalse();
    }

    @Test
    void neverFlagsEverythingSlowWhenTheThresholdIsAbsurdlyLarge() {
        // Converting such a threshold to microseconds overflows. No recorded duration can reach it, so it
        // must stay unreachable instead of wrapping into a negative bound that would flag everything.
        for (long threshold : new long[] {Long.MAX_VALUE / 1_000L + 1, Long.MAX_VALUE}) {
            SqlTraceRecorder recorder = recorder(true, false, 10, threshold);
            assertThat(recorder.isSlow(1)).isFalse();
            assertThat(recorder.isSlow(Long.MAX_VALUE)).isFalse();
        }
    }

    @Test
    void keepsTheLargestRepresentableThresholdExact() {
        long threshold = Long.MAX_VALUE / 1_000L;
        SqlTraceRecorder recorder = recorder(true, false, 10, threshold);
        assertThat(recorder.isSlow(threshold * 1_000L - 1)).isFalse();
        assertThat(recorder.isSlow(threshold * 1_000L)).isTrue();
        assertThat(recorder.isSlow(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void slowFlaggingDisabledWhenThresholdZero() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 0);
        assertThat(recorder.isSlow(5_000_000)).isFalse();
    }

    @Test
    void tracksWrappedDataSourceNames() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        assertThat(recorder.hasWrappedDataSource()).isFalse();
        recorder.registerDataSource("dataSource");
        recorder.registerDataSource("dataSource");
        recorder.registerDataSource(" ");
        assertThat(recorder.hasWrappedDataSource()).isTrue();
        assertThat(recorder.dataSourceNames()).containsExactly("dataSource");
    }

    @Test
    void computesAggregateStats() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(
                StatementType.STATEMENT,
                Category.SELECT,
                "select",
                List.of(),
                10_000,
                true,
                null,
                null,
                0,
                "c1",
                "main");
        recorder.record(
                StatementType.PREPARED, Category.UPDATE, "update", List.of(), 200_000, true, null, 3L, 0, "c1", "main");
        recorder.record(
                StatementType.PREPARED,
                Category.INSERT,
                "insert",
                List.of(),
                50_000,
                false,
                "boom",
                null,
                5,
                "c1",
                "main");

        SqlTraceStatsDto stats = recorder.stats();
        assertThat(stats.totalQueries()).isEqualTo(3);
        assertThat(stats.totalDurationMillis()).isEqualTo(260);
        assertThat(stats.maxDurationMillis()).isEqualTo(200);
        assertThat(stats.slowQueries()).isEqualTo(1);
        assertThat(stats.failedQueries()).isEqualTo(1);
        assertThat(stats.batchExecutions()).isEqualTo(1);
        assertThat(stats.selectCount()).isEqualTo(1);
        assertThat(stats.updateCount()).isEqualTo(1);
        assertThat(stats.insertCount()).isEqualTo(1);
        assertThat(stats.deleteCount()).isZero();
    }

    @Test
    void keepsSubMillisecondExecutionsOutOfTheStatsRatherThanTruncatingThemToZero() {
        SqlTraceRecorder recorder = recorder(true, false, 500, 100);
        for (int i = 0; i < 200; i++) {
            recorder.record(
                    StatementType.PREPARED,
                    Category.SELECT,
                    "select * from users where id = ?",
                    List.of(),
                    310,
                    true,
                    null,
                    null,
                    0,
                    "c1",
                    "main");
        }

        SqlTraceStatsDto stats = recorder.stats();
        assertThat(stats.totalQueries()).isEqualTo(200);
        assertThat(stats.totalDurationMillis()).isEqualTo(62.0);
        assertThat(stats.maxDurationMillis()).isEqualTo(0.31);
        assertThat(stats.avgDurationMillis()).isEqualTo(0.31);
        assertThat(stats.slowQueries()).isZero();

        SqlTraceEntryDto entry = recorder.entries(false).get(0);
        assertThat(entry.durationMicros()).isEqualTo(310);
        assertThat(entry.durationMillis()).isZero();
        assertThat(entry.slow()).isFalse();
    }

    @Test
    void roundsTheCompatibilityMillisecondFieldInsteadOfTruncatingIt() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                "select 1",
                List.of(),
                1_600,
                true,
                null,
                null,
                0,
                "c1",
                "main");

        SqlTraceEntryDto entry = recorder.entries(false).get(0);
        assertThat(entry.durationMicros()).isEqualTo(1_600);
        assertThat(entry.durationMillis()).isEqualTo(2);
    }

    @Test
    void groupsRepeatedSelectsAndFlagsNPlusOne() {
        SqlTraceRecorder recorder = recorder(true, false, 100, 100);
        for (int i = 0; i < 6; i++) {
            record(recorder, Category.SELECT, "select * from child where parent_id = ?", 0);
        }
        record(recorder, Category.SELECT, "select * from parent", 0);
        record(recorder, Category.UPDATE, "update parent set x = ?", 0);

        List<SqlTraceGroupDto> groups = recorder.topStatements();
        assertThat(groups).hasSize(3);
        SqlTraceGroupDto top = groups.get(0);
        assertThat(top.sql()).isEqualTo("select * from child where parent_id = ?");
        assertThat(top.executions()).isEqualTo(6);
        assertThat(top.potentialNPlusOne()).isTrue();
        assertThat(top.callSites()).isEmpty();
        assertThat(groups.stream().filter(SqlTraceGroupDto::potentialNPlusOne)).hasSize(1);
    }

    @Test
    void omitsCallSiteWhenCaptureDisabled() {
        SqlTraceRecorder recorder = recorder(true, false, false, 10, 100);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).callSite()).isNull();
    }

    @Test
    void neverThrowsAndStillRecordsWhenCallSiteCaptureIsEnabled() {
        SqlTraceRecorder recorder = recorder(true, false, true, 10, 100);
        record(recorder, Category.SELECT, "select 1", 0);
        // Best-effort: within this test suite's own call stack every frame belongs to a BootUI module package,
        // the JDK, JUnit, or the build tool (see StackFramePrefixes), so no application frame is ever found here
        // and the call site is null. The important guarantee under test is that enabling capture never throws
        // or disrupts recording; the frame-selection algorithm itself (with a synthetic application frame)
        // is covered in isolation by the selectCallSite* tests below.
        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).callSite()).isNull();
    }

    @Test
    void selectCallSiteFindsFirstApplicationFrame() {
        StackWalker.StackFrame jdk = frame("java.sql.Statement", "execute", "Statement.java", 10);
        StackWalker.StackFrame app = frame("com.example.app.OrderRepository", "findAll", "OrderRepository.java", 42);

        String result = SqlTraceRecorder.selectCallSite(Stream.of(jdk, app));

        assertThat(result).isEqualTo("com.example.app.OrderRepository.findAll(OrderRepository.java:42)");
    }

    @Test
    void selectCallSiteSkipsFrameworkAndBootUiFramesToFindTheApplicationFrame() {
        StackWalker.StackFrame bootui = frame(
                "io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder", "record", "SqlTraceRecorder.java", 200);
        StackWalker.StackFrame hibernate = frame("org.hibernate.engine.spi.SessionImpl", "list", "SessionImpl.java", 5);
        StackWalker.StackFrame app = frame("com.example.app.OrderRepository", "findAll", "OrderRepository.java", 42);

        String result = SqlTraceRecorder.selectCallSite(Stream.of(bootui, hibernate, app));

        assertThat(result).isEqualTo("com.example.app.OrderRepository.findAll(OrderRepository.java:42)");
    }

    @Test
    void selectCallSiteTreatsTheSampleApplicationsAsApplicationCode() {
        StackWalker.StackFrame bootui = frame(
                "io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder", "record", "SqlTraceRecorder.java", 200);
        StackWalker.StackFrame quarkus = frame(
                "io.github.jdubois.bootui.quarkus.sqltrace.BootUiHibernateStatementInspector",
                "inspect",
                "BootUiHibernateStatementInspector.java",
                40);
        StackWalker.StackFrame hibernate = frame("org.hibernate.engine.spi.SessionImpl", "list", "SessionImpl.java", 5);
        StackWalker.StackFrame sample = frame(
                "io.github.jdubois.bootui.sample.catalog.SampleCatalog", "searchProducts", "SampleCatalog.java", 34);

        String result = SqlTraceRecorder.selectCallSite(Stream.of(bootui, quarkus, hibernate, sample));

        assertThat(result)
                .isEqualTo(
                        "io.github.jdubois.bootui.sample.catalog.SampleCatalog.searchProducts(SampleCatalog.java:34)");
    }

    @Test
    void selectCallSiteReturnsNullWhenEveryFrameIsFrameworkOrBootUiCode() {
        StackWalker.StackFrame bootui = frame(
                "io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder", "record", "SqlTraceRecorder.java", 200);
        StackWalker.StackFrame jdk = frame("java.sql.Statement", "execute", "Statement.java", 10);

        assertThat(SqlTraceRecorder.selectCallSite(Stream.of(bootui, jdk))).isNull();
    }

    @Test
    void selectCallSiteRendersUnknownSourceWhenFileNameIsMissing() {
        StackWalker.StackFrame app = frame("com.example.app.OrderRepository", "findAll", null, 42);

        assertThat(SqlTraceRecorder.selectCallSite(Stream.of(app)))
                .isEqualTo("com.example.app.OrderRepository.findAll(Unknown Source)");
    }

    @Test
    void selectCallSiteOmitsLineNumberWhenNegative() {
        StackWalker.StackFrame app = frame("com.example.app.OrderRepository", "findAll", "OrderRepository.java", -1);

        assertThat(SqlTraceRecorder.selectCallSite(Stream.of(app)))
                .isEqualTo("com.example.app.OrderRepository.findAll(OrderRepository.java)");
    }

    @Test
    void selectCallSiteGivesUpBeyondTheFrameLimit() {
        StackWalker.StackFrame framework = frame("java.sql.Statement", "execute", "Statement.java", 10);
        StackWalker.StackFrame app = frame("com.example.app.OrderRepository", "findAll", "OrderRepository.java", 42);
        // 130 framework frames, then one application frame — placed beyond the 128-frame bound so the walk
        // must give up (return null) rather than finding it.
        Stream<StackWalker.StackFrame> frames =
                Stream.concat(Stream.generate(() -> framework).limit(130), Stream.of(app));

        assertThat(SqlTraceRecorder.selectCallSite(frames)).isNull();
    }

    private static StackWalker.StackFrame frame(String className, String methodName, String fileName, int lineNumber) {
        StackWalker.StackFrame frame = mock(StackWalker.StackFrame.class);
        when(frame.getClassName()).thenReturn(className);
        when(frame.getMethodName()).thenReturn(methodName);
        when(frame.getFileName()).thenReturn(fileName);
        when(frame.getLineNumber()).thenReturn(lineNumber);
        return frame;
    }

    @Test
    void clearEmptiesBufferButKeepsTotalCaptured() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        record(recorder, Category.SELECT, "select", 0);
        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);

        // totalCaptured is the lifetime count; the stats and entries cover the retained window, which clear empties.
        SqlTraceReport report = recorder.report(false);
        assertThat(report.totalCaptured()).isEqualTo(1);
        assertThat(report.entries()).isEmpty();
        assertThat(report.stats().totalQueries()).isZero();
        assertThat(McpControlAcks.sqlTrace(McpControlAcks.CLEARED, report))
                .containsEntry("action", "cleared")
                .containsEntry("retained", 0)
                .containsEntry("totalCaptured", 1L);
    }

    @Test
    void notifiesSubscribersOnRecordClearAndRecordingChange() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        Runnable handle = recorder.subscribe(notifications::incrementAndGet);

        record(recorder, Category.SELECT, "select", 0);
        assertThat(notifications.get()).isEqualTo(1);

        recorder.clear();
        assertThat(notifications.get()).isEqualTo(2);

        recorder.setRecording(false);
        assertThat(notifications.get()).isEqualTo(3);
        // No change in value -> no extra notification.
        recorder.setRecording(false);
        assertThat(notifications.get()).isEqualTo(3);

        handle.run();
        recorder.setRecording(true);
        record(recorder, Category.SELECT, "select", 0);
        assertThat(notifications.get()).isEqualTo(3);
    }

    @Test
    void stampsTraceIdFromConfiguredProvider() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-x", null));
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isEqualTo("trace-x");
    }

    @Test
    void usesNoTraceIdByDefault() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void treatsBlankProviderTraceIdAsNone() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("   ", null));
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void nullProviderRestoresDefaultAndNeverThrows() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.setCorrelationContextProvider(null);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void guardsAgainstThrowingProvider() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void enrichesActiveSpanPerStatementAndFlagsNPlusOneForTheTrace() {
        SqlTraceRecorder recorder = recorder(true, false, 100, 100);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-1", null));
        RecordingSpanEnricher enricher = new RecordingSpanEnricher();
        recorder.setSpanEnricher(enricher);

        // Five repeated selects on the same trace trip the default N+1 threshold (5).
        for (int i = 0; i < 5; i++) {
            record(recorder, Category.SELECT, "select * from item where order_id = ?", 0);
        }

        assertThat(enricher.calls).hasSize(5);
        assertThat(enricher.calls.get(0)).isFalse();
        assertThat(enricher.calls.get(4)).isTrue();
    }

    @Test
    void noOpEnricherSkipsPerTraceGrouping() {
        SqlTraceRecorder recorder = recorder(true, false, 100, 100);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-1", null));
        // Default enricher is NO_OP (disabled): recording still works and nothing throws.
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).hasSize(1);
    }

    private static final class RecordingSpanEnricher implements io.github.jdubois.bootui.engine.telemetry.SpanEnricher {
        private final List<Boolean> calls = new java.util.ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void onSqlStatement(java.util.function.BooleanSupplier nPlusOneSuspected) {
            calls.add(nPlusOneSuspected != null && nPlusOneSuspected.getAsBoolean());
        }
    }

    @Test
    void stampsTheRequestIdOfTheOpenScope() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2000, 200, 5);

        try (io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.Scope ignored =
                io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.open(
                        io.github.jdubois.bootui.spi.CorrelationContext.forRequest("0123456789abcdef"))) {
            recorder.record(
                    StatementType.PREPARED,
                    Category.SELECT,
                    "select 1",
                    List.of(),
                    10,
                    true,
                    null,
                    null,
                    0,
                    "c1",
                    "t1");
        }
        recorder.record(
                StatementType.PREPARED, Category.SELECT, "select 2", List.of(), 10, true, null, null, 0, "c1", "t1");

        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::requestId)
                .containsExactly(null, "0123456789abcdef");
    }

    @Test
    void usesTheInstalledCorrelationProviderAndSurvivesItsFailure() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2000, 200, 5);
        recorder.setCorrelationContextProvider(
                () -> io.github.jdubois.bootui.spi.CorrelationContext.forRequest("fedcba9876543210"));
        recorder.record(
                StatementType.PREPARED, Category.SELECT, "select 1", List.of(), 10, true, null, null, 0, "c1", "t1");
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("broken");
        });
        recorder.record(
                StatementType.PREPARED, Category.SELECT, "select 2", List.of(), 10, true, null, null, 0, "c1", "t1");

        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::requestId)
                .containsExactly(null, "fedcba9876543210");
    }
}
