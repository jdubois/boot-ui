package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * Proves the reserved-entry rule Live Activity persistence uses agrees with every failure-preserving buffer, for every
 * combination of status, outcome, duration, and threshold: each record is captured by the real buffer (whose reserved
 * count says whether it flagged the record), rendered by the real {@link LiveActivityAssembler}, and classified.
 */
class ReservedActivityEntriesTests {

    private static final Integer[] STATUSES = {null, 0, 200, 302, 399, 400, 404, 499, 500, 503};
    private static final long[] REQUEST_THRESHOLDS = {0L, 1_000L};

    @Test
    void aRequestEntryIsReservedExactlyWhenTheExchangeBufferReservesItsExchange() {
        SoftAssertions softly = new SoftAssertions();
        int reserved = 0;
        int combinations = 0;
        for (long threshold : REQUEST_THRESHOLDS) {
            for (Integer status : STATUSES) {
                if (status == null) {
                    continue;
                }
                for (Long durationMs : Arrays.asList(null, 0L, 999L, 1_000L, 1_500L)) {
                    HttpExchangeBuffer buffer = new HttpExchangeBuffer(10, 90, threshold);
                    buffer.record(new CapturedHttpExchange(
                            Instant.ofEpochMilli(1_000L),
                            "GET",
                            URI.create("http://localhost:8080/api/orders"),
                            status,
                            durationMs,
                            "127.0.0.1",
                            null,
                            null,
                            Map.of(),
                            Map.of(),
                            null));
                    boolean bufferReserved = buffer.retention().reserved() == 1;
                    reserved += bufferReserved ? 1 : 0;
                    combinations++;
                    HttpExchangesReport report = new HttpExchangesService()
                            .report(
                                    buffer.snapshot(),
                                    HttpExchangesService.BootUiSelfPath.EXCLUDED_AT_CAPTURE,
                                    true,
                                    ValueExposure.MASKED,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    ActivityEntryDto entry = only(
                            new LiveActivityAssembler(threshold)
                                    .report(
                                            report, List.of(), false, null, List.of(), List.of(), false, List.of(),
                                            false, List.of(), "UP", 0, List.of(), false, List.of(), false, List.of(),
                                            false, List.of(), false),
                            "REQUEST");

                    softly.assertThat(new ReservedActivityEntries(threshold).test(entry))
                            .as(
                                    "%s request, %s ms, threshold %s ms, severity %s",
                                    status, durationMs, threshold, entry.severity())
                            .isEqualTo(bufferReserved);
                }
            }
        }
        softly.assertThat(reserved)
                .as("the matrix covers reserved and routine exchanges")
                .isBetween(1, combinations - 1);
        softly.assertAll();
    }

    @Test
    void aSlowClientErrorIsShownAsAWarningYetReserved() {
        HttpExchangesReport report = new HttpExchangesService()
                .report(
                        List.of(new CapturedHttpExchange(
                                Instant.ofEpochMilli(1_000L),
                                "GET",
                                URI.create("http://localhost:8080/api/orders/42"),
                                404,
                                1_500L,
                                "127.0.0.1",
                                null,
                                null,
                                Map.of(),
                                Map.of(),
                                null)),
                        HttpExchangesService.BootUiSelfPath.EXCLUDED_AT_CAPTURE,
                        true,
                        ValueExposure.MASKED,
                        null,
                        null,
                        null,
                        null,
                        null);
        ActivityEntryDto entry = only(
                new LiveActivityAssembler(1_000L)
                        .report(
                                report, List.of(), false, null, List.of(), List.of(), false, List.of(), false,
                                List.of(), "UP", 0, List.of(), false, List.of(), false, List.of(), false, List.of(),
                                false),
                "REQUEST");

        assertThat(entry.severity()).as("status class wins over slowness").isEqualTo("WARN");
        assertThat(new ReservedActivityEntries(1_000L).test(entry)).isTrue();
        assertThat(new ReservedActivityEntries(0L).test(entry))
                .as("slow classification disabled")
                .isFalse();
        assertThat(new ReservedActivityEntries(2_000L).test(entry)).isFalse();
    }

    @Test
    void aSqlEntryIsReservedExactlyWhenSqlTraceReservesItsStatement() {
        SoftAssertions softly = new SoftAssertions();
        int reserved = 0;
        for (long threshold : new long[] {0L, 100L}) {
            for (boolean success : new boolean[] {true, false}) {
                for (long durationMicros : new long[] {0L, 99_999L, 100_000L, 250_000L}) {
                    SqlTraceRecorder recorder =
                            new SqlTraceRecorder(true, true, false, false, 10, threshold, 2_000, 200, 5, 90);
                    recorder.record(
                            SqlTraceRecorder.StatementType.PREPARED,
                            SqlTraceRecorder.Category.SELECT,
                            "select * from orders where id = ?",
                            List.of("42"),
                            durationMicros,
                            success,
                            success ? null : "deadlock detected",
                            null,
                            0,
                            "conn-1",
                            "main");
                    boolean bufferReserved = recorder.retention().reserved() == 1;
                    reserved += bufferReserved ? 1 : 0;
                    List<SqlTraceEntryDto> entries = recorder.report(false).entries();
                    ActivityEntryDto entry = only(
                            new LiveActivityAssembler()
                                    .report(
                                            null, entries, true, null, List.of(), List.of(), false, List.of(), false,
                                            List.of(), "UP", 0, List.of(), false, List.of(), false, List.of(), false,
                                            List.of(), false),
                            "SQL");

                    // No request threshold may change a SQL entry's classification.
                    for (long requestThreshold : REQUEST_THRESHOLDS) {
                        softly.assertThat(new ReservedActivityEntries(requestThreshold).test(entry))
                                .as(
                                        "success=%s, %s µs, SQL threshold %s ms, severity %s",
                                        success, durationMicros, threshold, entry.severity())
                                .isEqualTo(bufferReserved);
                    }
                }
            }
        }
        softly.assertThat(reserved)
                .as("the matrix covers reserved and routine statements")
                .isBetween(1, 15);
        softly.assertAll();
    }

    @Test
    void aRestClientEntryIsReservedExactlyWhenRestClientTraceReservesItsCall() {
        SoftAssertions softly = new SoftAssertions();
        int reserved = 0;
        int combinations = 0;
        for (long threshold : new long[] {0L, 1_000L}) {
            for (boolean success : new boolean[] {true, false}) {
                for (Integer status : STATUSES) {
                    for (long durationMillis : new long[] {5L, 999L, 1_000L, 1_500L}) {
                        RestClientTraceRecorder recorder =
                                new RestClientTraceRecorder(true, true, false, false, 10, threshold, 2_000, 200, 5, 90);
                        recorder.record(
                                "GET",
                                "http://api.example.com/items",
                                "api.example.com",
                                "/items",
                                status,
                                durationMillis,
                                success,
                                success ? null : "Connection refused",
                                "RestClient",
                                Map.of(),
                                "main");
                        boolean bufferReserved = recorder.retention().reserved() == 1;
                        reserved += bufferReserved ? 1 : 0;
                        combinations++;
                        List<RestClientTraceEntryDto> entries =
                                recorder.report(true, ValueExposure.MASKED).entries();
                        ActivityEntryDto entry = only(
                                new LiveActivityAssembler()
                                        .report(
                                                null, List.of(), false, null, List.of(), List.of(), false, List.of(),
                                                false, List.of(), "UP", 0, List.of(), false, List.of(), false,
                                                List.of(), false, entries, true),
                                "REST_CLIENT");

                        for (long requestThreshold : REQUEST_THRESHOLDS) {
                            softly.assertThat(new ReservedActivityEntries(requestThreshold).test(entry))
                                    .as(
                                            "success=%s, status %s, %s ms, REST threshold %s ms, severity %s",
                                            success, status, durationMillis, threshold, entry.severity())
                                    .isEqualTo(bufferReserved);
                        }
                    }
                }
            }
        }
        softly.assertThat(reserved)
                .as("the matrix covers reserved and routine calls")
                .isBetween(1, combinations - 1);
        softly.assertAll();
    }

    @Test
    void entriesFromBuffersWithoutAReservedShareAreNeverReserved() {
        ReservedActivityEntries rule = new ReservedActivityEntries(1_000L);
        List<String> types = List.of(
                "EXCEPTION", "SECURITY", "MAIL", "CACHE", "SCHEDULED", "MESSAGING", "FAULT_TOLERANCE", "UNKNOWN");
        List<ActivityEntryDto> entries = new ArrayList<>();
        for (String type : types) {
            for (String severity : List.of("OK", "SLOW", "WARN", "ERROR")) {
                entries.add(new ActivityEntryDto(
                        type + "-" + severity,
                        type,
                        1L,
                        severity,
                        "summary",
                        null,
                        5_000L,
                        null,
                        null,
                        null,
                        500,
                        null,
                        false,
                        null,
                        null,
                        false));
            }
        }

        assertThat(entries).noneMatch(rule);
        assertThat(rule.test(null)).isFalse();
        assertThat(rule.test(new ActivityEntryDto(
                        "no-type", null, 1L, "ERROR", "summary", null, null, null, null, null, 500, null, false, null,
                        null, false)))
                .isFalse();
    }

    @Test
    void aNegativeThresholdDisablesSlowClassificationLikeTheBuffer() {
        ReservedActivityEntries rule = new ReservedActivityEntries(-5L);

        assertThat(rule.requestSlowThresholdMillis()).isZero();
        assertThat(rule.test(request(404, 60_000L))).isFalse();
        assertThat(rule.test(request(500, null))).isTrue();
    }

    @Test
    void aRequestWithoutAStatusIsClassifiedAsAnExchangeWithoutAResponse() {
        ReservedActivityEntries rule = new ReservedActivityEntries(1_000L);

        assertThat(rule.test(request(null, 5L))).isFalse();
        assertThat(rule.test(request(null, 1_500L))).isTrue();
    }

    private static ActivityEntryDto request(Integer status, Long durationMs) {
        return new ActivityEntryDto(
                "req",
                "REQUEST",
                1L,
                "WARN",
                "GET /orders",
                null,
                durationMs,
                null,
                "GET",
                "/orders",
                status,
                null,
                false,
                null,
                null,
                false);
    }

    private static ActivityEntryDto only(LiveActivityReport report, String type) {
        List<ActivityEntryDto> entries = report.entries().stream()
                .filter(entry -> type.equals(entry.type()))
                .toList();
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }
}
