package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the trace-id correlation both Quarkus and Spring WebFlux rely on for Live Activity: when the
 * captured signals share a distributed-trace id, the assembler nests the SQL/REST-client/exception/security
 * entries under the owning REQUEST entry by setting their {@code parentId} (a uniquely-matched security
 * event additionally stamps {@code securedPrincipal} on that request); when no shared trace id is present
 * the feed stays flat; and an ambiguous trace id shared by more than one request never nests a child under
 * the wrong one nor stamps a principal.
 */
class LiveActivityAssemblerTests {

    private final LiveActivityAssembler assembler = new LiveActivityAssembler();

    @Test
    void nestsSqlAndExceptionUnderRequestSharingTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-a", 1_020L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions, List.of(), false, List.of(), false, "UP", 0);

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

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions, List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void leavesSignalsFlatWhenNoRequestSharesTheirTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-orphan", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-orphan", 1_020L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions, List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void doesNotNestWhenTwoRequestsShareTheSameTraceId() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/orders", "trace-a", 2_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isNull();
    }

    @Test
    void nestsOnlyTheChildrenWhoseTraceIdMatchesAUniqueRequest() {
        HttpExchangesReport requests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-b", 2_000L));
        List<SqlTraceEntryDto> sql =
                List.of(sql(10, "select 1", "trace-a", 1_010L), sql(11, "select 2", "trace-b", 2_010L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("req-1");
        assertThat(entry(report, "sql-11").parentId()).isEqualTo("req-2");
    }

    @Test
    void nestsSecurityEntryUnderRequestSharingTraceIdAndSetsSecuredPrincipal() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), security, true, List.of(), false, "UP", 0);

        ActivityEntryDto securityEntry = securityEntry(report);
        assertThat(securityEntry.type()).isEqualTo("SECURITY");
        assertThat(securityEntry.severity()).isEqualTo("OK");
        assertThat(securityEntry.summary()).isEqualTo("AUTHENTICATION_SUCCESS · alice");
        assertThat(securityEntry.correlationId()).isEqualTo("trace-a");
        assertThat(securityEntry.parentId()).isEqualTo("req-1");
        assertThat(entry(report, "req-1").securedPrincipal()).isEqualTo("alice");
        assertThat(report.sources()).contains("security");
    }

    @Test
    void mapsFailureSecurityEventTypeToWarnSeverity() {
        HttpExchangesReport requests = requests(request("req-1", "/login", "trace-a", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("bob", "AUTHENTICATION_FAILURE", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), security, true, List.of(), false, "UP", 0);

        assertThat(securityEntry(report).severity()).isEqualTo("WARN");
    }

    @Test
    void doesNotNestAmbiguousSecurityEventAndDoesNotStampSecuredPrincipal() {
        HttpExchangesReport requests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-a", 2_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), security, true, List.of(), false, "UP", 0);

        assertThat(securityEntry(report).parentId()).isNull();
        assertThat(entry(report, "req-1").securedPrincipal()).isNull();
        assertThat(entry(report, "req-2").securedPrincipal()).isNull();
    }

    @Test
    void leavesSecurityEntryFlatWhenNoTraceIdIsStamped() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", null, 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", null, 1_010L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), security, true, List.of(), false, "UP", 0);

        ActivityEntryDto securityEntry = securityEntry(report);
        assertThat(securityEntry.parentId()).isNull();
        assertThat(securityEntry.correlationId()).isNull();
        assertThat(entry(report, "req-1").securedPrincipal()).isNull();
    }

    @Test
    void prefersTheRequestsOwnPrincipalOverACorrelatedSecurityEvent() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", "bob", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), security, true, List.of(), false, "UP", 0);

        assertThat(entry(report, "req-1").securedPrincipal()).isEqualTo("bob");
    }

    @Test
    void omitsSecuritySourceWhenUnavailable() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(report.sources()).doesNotContain("security");
    }

    @Test
    void flagsRequestAsSqlNPlusOneSuspectedWhenItsCorrelatedSqlHitsTheThreshold() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L),
                sql(5, "select * from item where order_id = ?", "trace-a", 1_005L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "req-1").sqlNPlusOneSuspected()).isTrue();
    }

    @Test
    void doesNotFlagSqlNPlusOneSuspectedBelowTheThreshold() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "req-1").sqlNPlusOneSuspected()).isFalse();
    }

    @Test
    void nonRequestEntriesAreNeverFlaggedAsSqlNPlusOneSuspected() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L),
                sql(5, "select * from item where order_id = ?", "trace-a", 1_005L));

        LiveActivityReport report =
                assembler.report(requests, sql, true, null, exceptions(), List.of(), false, List.of(), false, "UP", 0);

        assertThat(entry(report, "sql-1").sqlNPlusOneSuspected()).isFalse();
    }

    @Test
    void mapsRestClientEntrySeveritySummaryDetailAndParentFromTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<RestClientTraceEntryDto> rest =
                List.of(rest(7, "GET", "api.example.com", "/users", 200, 75L, true, null, false, "trace-a"));

        LiveActivityReport report =
                assembler.report(requests, List.of(), false, null, exceptions(), List.of(), false, rest, true, "UP", 0);

        ActivityEntryDto restEntry = entry(report, "rest-7");
        assertThat(restEntry.type()).isEqualTo("REST_CLIENT");
        assertThat(restEntry.severity()).isEqualTo("OK");
        assertThat(restEntry.summary()).isEqualTo("GET api.example.com/users → 200");
        assertThat(restEntry.detail()).isEqualTo("WebClient");
        assertThat(restEntry.method()).isEqualTo("GET");
        assertThat(restEntry.path()).isEqualTo("/users");
        assertThat(restEntry.status()).isEqualTo(200);
        assertThat(restEntry.parentId()).isEqualTo("req-1");
        assertThat(restEntry.correlationId()).isEqualTo("trace-a");
        assertThat(report.sources()).contains("rest-client");
    }

    @Test
    void leavesRestClientEntryFlatWhenNoTraceIdIsStampedOrItIsAmbiguous() {
        HttpExchangesReport missingTraceRequest = requests(request("req-1", "/orders", null, 1_000L));
        LiveActivityReport missingTraceReport = assembler.report(
                missingTraceRequest,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(rest(7, "GET", "api.example.com", "/users", 200, 75L, true, null, false, null)),
                true,
                "UP",
                0);
        assertThat(entry(missingTraceReport, "rest-7").parentId()).isNull();

        HttpExchangesReport ambiguousRequests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-a", 2_000L));
        LiveActivityReport ambiguousReport = assembler.report(
                ambiguousRequests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(rest(8, "GET", "api.example.com", "/users", 200, 75L, true, null, false, "trace-a")),
                true,
                "UP",
                0);
        assertThat(entry(ambiguousReport, "rest-8").parentId()).isNull();
    }

    @Test
    void computesRestClientKpis() {
        List<RestClientTraceEntryDto> rest = List.of(
                rest(1, "GET", "api", "/ok", 200, 10L, true, null, false, "trace-a"),
                rest(2, "GET", "api", "/slow", 404, 20L, true, null, false, "trace-b"),
                rest(3, "GET", "api", "/down", null, 30L, false, "boom", false, "trace-c"),
                rest(4, "GET", "api", "/slower", 200, 40L, true, null, true, "trace-d"));

        LiveActivityReport report = assembler.report(
                requests(), List.of(), false, null, exceptions(), List.of(), false, rest, true, "UP", 0);

        assertThat(report.kpis().restCallErrorRatePercent()).isEqualTo(50.0);
        assertThat(report.kpis().restCallP95LatencyMs()).isEqualTo(40L);
    }

    @Test
    void omitsRestClientSourceAndKpisWhenUnavailable() {
        LiveActivityReport report = assembler.report(
                requests(),
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(rest(1, "GET", "api", "/ok", 200, 10L, true, null, false, "trace-a")),
                false,
                "UP",
                0);

        assertThat(report.sources()).doesNotContain("rest-client");
        assertThat(report.kpis().restCallErrorRatePercent()).isNull();
        assertThat(report.kpis().restCallP95LatencyMs()).isNull();
    }

    private static ActivityEntryDto entry(LiveActivityReport report, String id) {
        return report.entries().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry with id " + id));
    }

    /**
     * Security entry ids are a content hash (see {@link SecurityActivityIds}), not a predictable literal,
     * so tests locate the single security entry by type instead of hardcoding an id.
     */
    private static ActivityEntryDto securityEntry(LiveActivityReport report) {
        return report.entries().stream()
                .filter(e -> "SECURITY".equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SECURITY entry in report"));
    }

    private static HttpExchangesReport requests(HttpExchangeDto... exchanges) {
        List<HttpExchangeDto> list = List.of(exchanges);
        return new HttpExchangesReport(
                list.size(), list.size(), 0, list, new PageMetadata(0, list.size(), list.size(), 1, 1, false), null);
    }

    private static HttpExchangeDto request(String id, String path, String traceId, long epochMillis) {
        return request(id, path, traceId, null, epochMillis);
    }

    private static HttpExchangeDto request(String id, String path, String traceId, String principal, long epochMillis) {
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
                principal,
                null,
                traceId,
                List.of(),
                List.of());
    }

    private static SecurityLogEventDto security(String principal, String type, String traceId, long epochMillis) {
        return new SecurityLogEventDto(
                Instant.ofEpochMilli(epochMillis).toString(), principal, type, List.of(), traceId);
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long epochMillis) {
        return sql(id, sql, traceId, epochMillis, null);
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long epochMillis, String callSite) {
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
                traceId,
                callSite);
    }

    private static RestClientTraceEntryDto rest(
            long id,
            String method,
            String host,
            String path,
            Integer status,
            long durationMillis,
            boolean success,
            String errorMessage,
            boolean slow,
            String traceId) {
        return new RestClientTraceEntryDto(
                id,
                1_000L + id,
                method,
                "https://" + host + path,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                slow,
                "WebClient",
                Map.of(),
                traceId,
                "worker-1",
                null);
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
                lastTraceId,
                "OPEN",
                0);
    }
}
