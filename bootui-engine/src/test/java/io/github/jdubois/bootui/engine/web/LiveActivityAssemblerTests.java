package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the trace-id correlation the Quarkus adapter relies on: when the captured signals share a
 * distributed-trace id, the assembler nests the SQL/exception entries under the owning REQUEST entry by
 * setting their {@code parentId}; when no shared trace id is present (OpenTelemetry absent) the feed stays
 * flat; and an ambiguous trace id shared by more than one request never nests a child under the wrong one.
 */
class LiveActivityAssemblerTests {

    private final LiveActivityAssembler assembler = new LiveActivityAssembler();

    @Test
    void nestsSqlAndExceptionUnderRequestSharingTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-a", 1_020L));

        LiveActivityReport report = assembler.report(requests, sql, true, null, exceptions, "UP", 0);

        ActivityEntryDto request = entry(report, "req-1");
        ActivityEntryDto sqlEntry = entry(report, "sql-10");
        ActivityEntryDto exceptionEntry = entry(report, "exc-g-1");

        assertThat(request.parentId()).isNull();
        assertThat(request.profileable()).isFalse();
        assertThat(sqlEntry.parentId()).isEqualTo("req-1");
        assertThat(sqlEntry.correlationId()).isEqualTo("trace-a");
        assertThat(exceptionEntry.parentId()).isEqualTo("req-1");
        assertThat(exceptionEntry.correlationId()).isEqualTo("trace-a");
    }

    @Test
    void leavesSignalsFlatWhenNoTraceIdIsStamped() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", null, 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", null, 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", null, 1_020L));

        LiveActivityReport report = assembler.report(requests, sql, true, null, exceptions, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void leavesSignalsFlatWhenNoRequestSharesTheirTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-orphan", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-orphan", 1_020L));

        LiveActivityReport report = assembler.report(requests, sql, true, null, exceptions, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void doesNotNestWhenTwoRequestsShareTheSameTraceId() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/orders", "trace-a", 2_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(requests, sql, true, null, exceptions(), "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
    }

    @Test
    void nestsOnlyTheChildrenWhoseTraceIdMatchesAUniqueRequest() {
        HttpExchangesReport requests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-b", 2_000L));
        List<SqlTraceEntryDto> sql =
                List.of(sql(10, "select 1", "trace-a", 1_010L), sql(11, "select 2", "trace-b", 2_010L));

        LiveActivityReport report = assembler.report(requests, sql, true, null, exceptions(), "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("req-1");
        assertThat(entry(report, "sql-11").parentId()).isEqualTo("req-2");
    }

    private static ActivityEntryDto entry(LiveActivityReport report, String id) {
        return report.entries().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry with id " + id));
    }

    private static HttpExchangesReport requests(HttpExchangeDto... exchanges) {
        List<HttpExchangeDto> list = List.of(exchanges);
        return new HttpExchangesReport(
                list.size(), list.size(), 0, list, new PageMetadata(0, list.size(), list.size(), 1, 1, false), null);
    }

    private static HttpExchangeDto request(String id, String path, String traceId, long epochMillis) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                path,
                null,
                "http://localhost:8080" + path,
                200,
                "2xx",
                12L,
                34L,
                "127.0.0.1",
                null,
                null,
                traceId,
                List.of(),
                List.of());
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long epochMillis) {
        return new SqlTraceEntryDto(
                id,
                epochMillis,
                sql,
                "PREPARED",
                "SELECT",
                5L,
                true,
                null,
                null,
                0,
                "c1",
                "worker-1",
                false,
                List.of(),
                traceId);
    }

    private static List<ExceptionGroupDto> exceptions() {
        return List.of();
    }

    private static ExceptionGroupDto exception(String id, String lastTraceId, long lastSeen) {
        return new ExceptionGroupDto(
                id,
                "java.lang.IllegalStateException",
                "boom",
                1,
                lastSeen,
                lastSeen,
                "Foo.java:1",
                true,
                "worker-1",
                "GET",
                "/orders",
                "Handler#x",
                "web",
                lastTraceId);
    }
}
