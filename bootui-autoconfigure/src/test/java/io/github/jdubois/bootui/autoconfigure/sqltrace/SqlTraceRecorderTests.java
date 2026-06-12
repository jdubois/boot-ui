package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTraceRecorderTests {

    private SqlTraceRecorder recorder(boolean enabled, boolean captureParameters, int maxEntries, long slowMillis) {
        return new SqlTraceRecorder(enabled, true, captureParameters, maxEntries, slowMillis, 2000, 200, 5);
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
        assertThat(groups.stream().filter(SqlTraceGroupDto::potentialNPlusOne)).hasSize(1);
    }

    @Test
    void clearEmptiesBufferButKeepsTotalCaptured() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        record(recorder, Category.SELECT, "select", 0);
        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }
}
