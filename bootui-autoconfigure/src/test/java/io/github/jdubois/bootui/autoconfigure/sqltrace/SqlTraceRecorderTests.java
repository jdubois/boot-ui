package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Operation;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTraceRecorderTests {

    private SqlTraceRecorder recorder(boolean enabled, boolean captureParameters, int maxEntries, long slowMillis) {
        return new SqlTraceRecorder(enabled, captureParameters, maxEntries, slowMillis, 2000, 200);
    }

    @Test
    void recordsNothingWhenDisabled() {
        SqlTraceRecorder recorder = recorder(false, false, 10, 100);
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "select 1", List.of(), 5, true, null, null, 0, "c1");
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void evictsOldestBeyondCapacityAndReturnsMostRecentFirst() {
        SqlTraceRecorder recorder = recorder(true, false, 2, 100);
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "first", List.of(), 1, true, null, null, 0, "c1");
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "second", List.of(), 1, true, null, null, 0, "c1");
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "third", List.of(), 1, true, null, null, 0, "c1");

        assertThat(recorder.recent())
                .extracting(SqlTraceRecorder.CapturedStatement::sql)
                .containsExactly("third", "second");
        assertThat(recorder.totalCaptured()).isEqualTo(3);
    }

    @Test
    void dropsParametersWhenCaptureDisabled() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(
                StatementType.PREPARED, Operation.QUERY, "select ?", List.of("'x'"), 1, true, null, null, 0, "c1");
        assertThat(recorder.recent().get(0).parameters()).isEmpty();
    }

    @Test
    void keepsParametersWhenCaptureEnabled() {
        SqlTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                StatementType.PREPARED, Operation.QUERY, "select ?", List.of("'x'"), 1, true, null, null, 0, "c1");
        assertThat(recorder.recent().get(0).parameters()).containsExactly("'x'");
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
    void computesAggregateStats() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "select", List.of(), 10, true, null, null, 0, "c1");
        recorder.record(StatementType.PREPARED, Operation.UPDATE, "update", List.of(), 200, true, null, 3L, 0, "c1");
        recorder.record(StatementType.PREPARED, Operation.BATCH, "insert", List.of(), 50, false, "boom", null, 5, "c1");

        SqlTraceStatsDto stats = recorder.stats();
        assertThat(stats.totalQueries()).isEqualTo(3);
        assertThat(stats.totalDurationMillis()).isEqualTo(260);
        assertThat(stats.maxDurationMillis()).isEqualTo(200);
        assertThat(stats.slowQueries()).isEqualTo(1);
        assertThat(stats.failedQueries()).isEqualTo(1);
        assertThat(stats.selectCount()).isEqualTo(1);
        assertThat(stats.updateCount()).isEqualTo(1);
        assertThat(stats.batchCount()).isEqualTo(1);
    }

    @Test
    void clearEmptiesBufferButKeepsTotalCaptured() {
        SqlTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(StatementType.STATEMENT, Operation.QUERY, "select", List.of(), 1, true, null, null, 0, "c1");
        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }
}
