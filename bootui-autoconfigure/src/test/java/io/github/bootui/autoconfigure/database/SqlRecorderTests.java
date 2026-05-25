package io.github.bootui.autoconfigure.database;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bootui.core.BootUiDtos.SqlRequestDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlRecorderTests {

    @Test
    void keepsRequestsInNewestFirstOrder() {
        SqlRecorder recorder = new SqlRecorder(3);
        recorder.record(make("select 1", 1));
        recorder.record(make("select 2", 2));
        recorder.record(make("select 3", 3));

        List<SqlRequestDto> snapshot = recorder.snapshot();
        assertThat(snapshot).extracting(SqlRequestDto::sql).containsExactly("select 3", "select 2", "select 1");
        assertThat(recorder.size()).isEqualTo(3);
        assertThat(recorder.totalRecorded()).isEqualTo(3);
    }

    @Test
    void evictsOldestWhenAtCapacity() {
        SqlRecorder recorder = new SqlRecorder(2);
        recorder.record(make("a", 1));
        recorder.record(make("b", 2));
        recorder.record(make("c", 3));
        recorder.record(make("d", 4));

        List<SqlRequestDto> snapshot = recorder.snapshot();
        assertThat(snapshot).extracting(SqlRequestDto::sql).containsExactly("d", "c");
        assertThat(recorder.size()).isEqualTo(2);
        assertThat(recorder.totalRecorded()).isEqualTo(4);
    }

    @Test
    void clearResetsBuffer() {
        SqlRecorder recorder = new SqlRecorder(2);
        recorder.record(make("a", 1));
        recorder.clear();
        assertThat(recorder.size()).isZero();
        assertThat(recorder.snapshot()).isEmpty();
    }

    @Test
    void ignoresNullRecord() {
        SqlRecorder recorder = new SqlRecorder(2);
        recorder.record(null);
        assertThat(recorder.size()).isZero();
        assertThat(recorder.totalRecorded()).isZero();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        try {
            new SqlRecorder(0);
            assertThat(false).as("expected exception").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("capacity");
        }
    }

    private static SqlRequestDto make(String sql, long timestamp) {
        return new SqlRequestDto(timestamp, "ds", sql, "PREPARED", 100L, true, null, null);
    }
}
