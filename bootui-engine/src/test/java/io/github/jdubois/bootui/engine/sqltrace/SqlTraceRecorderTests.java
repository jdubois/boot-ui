package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SqlTraceRecorderTests {

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
        assertThat(recorder.isSlow(150)).isTrue();
        assertThat(recorder.isSlow(50)).isFalse();
    }

    @Test
    void slowFlaggingDisabledWhenThresholdZero() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 0);
        assertThat(recorder.isSlow(5000)).isFalse();
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
                StatementType.STATEMENT, Category.SELECT, "select", List.of(), 10, true, null, null, 0, "c1", "main");
        recorder.record(
                StatementType.PREPARED, Category.UPDATE, "update", List.of(), 200, true, null, 3L, 0, "c1", "main");
        recorder.record(
                StatementType.PREPARED, Category.INSERT, "insert", List.of(), 50, false, "boom", null, 5, "c1", "main");

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
        // Best-effort: within this test suite's own call stack every frame belongs to BootUI, the JDK,
        // JUnit, or the build tool (see StackFramePrefixes), so no application frame is ever found here and
        // the call site is null. The important guarantee under test is that enabling capture never throws
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
        recorder.setTraceIdProvider(() -> "trace-x");
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
        recorder.setTraceIdProvider(() -> "   ");
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void nullProviderRestoresDefaultAndNeverThrows() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.setTraceIdProvider(null);
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void guardsAgainstThrowingProvider() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        record(recorder, Category.SELECT, "select 1", 0);
        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }
}
