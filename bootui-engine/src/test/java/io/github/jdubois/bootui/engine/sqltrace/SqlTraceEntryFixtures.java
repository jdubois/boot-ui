package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.util.List;

/** Builds SQL Trace entries for the ranking and attribution tests without repeating seventeen arguments. */
final class SqlTraceEntryFixtures {

    private static long nextId = 1;

    private SqlTraceEntryFixtures() {}

    static Builder entry(String sql) {
        return new Builder(sql);
    }

    static final class Builder {

        private final String sql;
        private long timestamp = 1_000L;
        private long durationMicros = 10_000L;
        private boolean success = true;
        private String category = "SELECT";
        private String statementType = "PREPARED";
        private String thread = "http-nio-8080-exec-1";
        private String traceId;
        private String callSite;

        private Builder(String sql) {
            this.sql = sql;
        }

        Builder at(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /** Duration in whole milliseconds, for the many tests that think in milliseconds. */
        Builder lasting(long durationMillis) {
            this.durationMicros = durationMillis * 1_000L;
            return this;
        }

        /** Duration in microseconds, for the sub-millisecond executions a local database really produces. */
        Builder lastingMicros(long durationMicros) {
            this.durationMicros = durationMicros;
            return this;
        }

        Builder failed() {
            this.success = false;
            return this;
        }

        Builder category(String category) {
            this.category = category;
            return this;
        }

        Builder statementType(String statementType) {
            this.statementType = statementType;
            return this;
        }

        Builder onThread(String thread) {
            this.thread = thread;
            return this;
        }

        Builder withTrace(String traceId) {
            this.traceId = traceId;
            return this;
        }

        Builder from(String callSite) {
            this.callSite = callSite;
            return this;
        }

        SqlTraceEntryDto build() {
            return new SqlTraceEntryDto(
                    nextId++,
                    timestamp,
                    sql,
                    statementType,
                    category,
                    durationMicros,
                    Math.round(durationMicros / 1_000.0),
                    success,
                    success ? null : "boom",
                    null,
                    0,
                    "conn-1",
                    thread,
                    false,
                    List.of(),
                    traceId,
                    callSite);
        }
    }

    /** Guards the fixture itself: a builder that silently dropped a field would weaken every test. */
    static void selfCheck() {
        SqlTraceEntryDto entry = entry("select 1")
                .at(5)
                .lasting(7)
                .failed()
                .withTrace("t")
                .onThread("th")
                .build();
        assertThat(entry.timestamp()).isEqualTo(5);
        assertThat(entry.durationMicros()).isEqualTo(7_000);
        assertThat(entry.durationMillis()).isEqualTo(7);
        assertThat(entry("select 1").lastingMicros(310).build().durationMicros())
                .isEqualTo(310);
        assertThat(entry.success()).isFalse();
        assertThat(entry.traceId()).isEqualTo("t");
        assertThat(entry.thread()).isEqualTo("th");
    }
}
