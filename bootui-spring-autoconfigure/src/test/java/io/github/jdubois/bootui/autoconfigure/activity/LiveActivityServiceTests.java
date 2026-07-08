package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LiveActivityServiceTests {

    private static final Instant BASE = Instant.parse("2026-06-14T10:00:00Z");

    @Test
    void mergesAndSortsSourcesNewestFirst() {
        LiveActivityService service = service(
                requests(
                        exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L),
                        exchange("r2", BASE.plusMillis(3000), "GET", "/b", 500, 12L)),
                sql(sqlEntry(1, BASE.plusMillis(2000).toEpochMilli(), "SELECT 1", "SELECT", 5, true, false)),
                exceptions(group(
                        "e1",
                        "java.lang.IllegalStateException",
                        BASE.plusMillis(2500).toEpochMilli())),
                null,
                null,
                new BootUiProperties());

        LiveActivityReport report = service.report(null, null, 0, 0);

        assertThat(report.available()).isTrue();
        assertThat(report.entries())
                .extracting(e -> e.type() + ":" + e.timestamp())
                .containsExactly(
                        "REQUEST:" + BASE.plusMillis(3000).toEpochMilli(),
                        "EXCEPTION:" + BASE.plusMillis(2500).toEpochMilli(),
                        "SQL:" + BASE.plusMillis(2000).toEpochMilli(),
                        "REQUEST:" + BASE.plusMillis(1000).toEpochMilli());
        assertThat(report.typeCounts())
                .containsEntry("REQUEST", 2)
                .containsEntry("SQL", 1)
                .containsEntry("EXCEPTION", 1);
    }

    @Test
    void classifiesSeverityFromStatusAndSlowness() {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setRequestSlowThresholdMs(100);
        LiveActivityService service = service(
                requests(
                        exchange("ok", BASE.plusMillis(10), "GET", "/ok", 200, 5L),
                        exchange("slow", BASE.plusMillis(20), "GET", "/slow", 200, 500L),
                        exchange("warn", BASE.plusMillis(30), "GET", "/warn", 404, 5L),
                        exchange("err", BASE.plusMillis(40), "GET", "/err", 500, 5L)),
                null,
                null,
                null,
                null,
                properties);

        LiveActivityReport report = service.report(null, null, 0, 0);

        assertThat(report.entries())
                .filteredOn(e -> e.type().equals("REQUEST"))
                .extracting(e -> e.id() + "=" + e.severity())
                .containsExactlyInAnyOrder("ok=OK", "slow=SLOW", "warn=WARN", "err=ERROR");
    }

    @Test
    void appliesTypeSeveritySinceAndLimitFilters() {
        LiveActivityService service = service(
                requests(
                        exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 5L),
                        exchange("r2", BASE.plusMillis(2000), "GET", "/b", 500, 5L)),
                sql(sqlEntry(1, BASE.plusMillis(1500).toEpochMilli(), "SELECT 1", "SELECT", 5, true, false)),
                null,
                null,
                null,
                new BootUiProperties());

        assertThat(service.report("REQUEST", null, 0, 0).entries())
                .allMatch(e -> e.type().equals("REQUEST"));
        assertThat(service.report(null, "ERROR", 0, 0).entries())
                .allMatch(e -> e.severity().equals("ERROR"));
        assertThat(service.report(null, null, BASE.plusMillis(1600).toEpochMilli(), 0)
                        .entries())
                .extracting(e -> e.id())
                .containsExactly("r2");
        assertThat(service.report(null, null, 0, 1).entries()).hasSize(1);
    }

    @Test
    void reportsUnavailableWhenNoSourcesPresent() {
        LiveActivityService service = service(null, null, null, null, null, new BootUiProperties());

        LiveActivityReport report = service.report(null, null, 0, 0);

        assertThat(report.available()).isFalse();
        assertThat(report.entries()).isEmpty();
        assertThat(report.sources()).isEmpty();
    }

    @Test
    void computesKpisFromRequestsAndSql() {
        LiveActivityService service = service(
                requests(
                        exchange("r1", BASE.plusMillis(0), "GET", "/fast", 200, 10L),
                        exchange("r2", BASE.plusMillis(60_000), "GET", "/slow", 500, 200L)),
                sql(sqlEntry(1, BASE.toEpochMilli(), "SELECT 1", "SELECT", 40, true, false)),
                null,
                null,
                null,
                new BootUiProperties());

        var kpis = service.report(null, null, 0, 0).kpis();

        assertThat(kpis.errorRatePercent()).isEqualTo(50.0);
        assertThat(kpis.slowestEndpoint()).isEqualTo("/slow");
        assertThat(kpis.slowestEndpointMs()).isEqualTo(200L);
        assertThat(kpis.slowestQueryMs()).isEqualTo(40L);
    }

    @Test
    void nestsSqlUnderRequestByTraceId() {
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                sql(sqlEntryOn(1, BASE.plusMillis(1010).toEpochMilli(), "http-thread", "trace-r1")),
                null,
                null,
                null,
                new BootUiProperties());

        assertThat(parentOf(service.report(null, null, 0, 0), "sql-1")).isEqualTo("r1");
    }

    @Test
    void nestsSqlUnderRequestByServingThread() {
        RequestCorrelationRegistry requestCorrelations = new RequestCorrelationRegistry(10);
        requestCorrelations.record(new RequestCorrelationRegistry.RequestCorrelation(
                BASE.plusMillis(1000).toEpochMilli(), BASE.plusMillis(1030).toEpochMilli(), "worker-9", "GET", "/a"));
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                sql(sqlEntryOn(1, BASE.plusMillis(1015).toEpochMilli(), "worker-9", null)),
                null,
                null,
                null,
                requestCorrelations,
                null,
                null,
                new BootUiProperties());

        assertThat(parentOf(service.report(null, null, 0, 0), "sql-1")).isEqualTo("r1");
    }

    @Test
    void nestsExceptionUnderRequestByMethodAndPath() {
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 500, 30L)),
                null,
                exceptions(group(
                        "e1",
                        "java.lang.IllegalStateException",
                        BASE.plusMillis(1020).toEpochMilli())),
                null,
                null,
                new BootUiProperties());

        assertThat(parentOf(service.report(null, null, 0, 0), "exc-e1")).isEqualTo("r1");
    }

    @Test
    void nestsSecurityUnderRequestByServingThread() {
        long ts = BASE.plusMillis(1015).toEpochMilli();
        SecurityEventCorrelationRegistry securityCorrelations = new SecurityEventCorrelationRegistry(10);
        securityCorrelations.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                ts, "worker-7", "AUTHENTICATION_SUCCESS", "alice"));
        RequestCorrelationRegistry requestCorrelations = new RequestCorrelationRegistry(10);
        requestCorrelations.record(new RequestCorrelationRegistry.RequestCorrelation(
                BASE.plusMillis(1000).toEpochMilli(), BASE.plusMillis(1030).toEpochMilli(), "worker-7", "GET", "/a"));
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                null,
                null,
                security(securityEvent("AUTHENTICATION_SUCCESS", "alice", ts)),
                null,
                requestCorrelations,
                securityCorrelations,
                null,
                new BootUiProperties());

        assertThat(parentOfSecurityEntry(service.report(null, null, 0, 0))).isEqualTo("r1");
    }

    @Test
    void marksSecuredRequestWithCorrelatedPrincipal() {
        long ts = BASE.plusMillis(1015).toEpochMilli();
        SecurityEventCorrelationRegistry securityCorrelations = new SecurityEventCorrelationRegistry(10);
        securityCorrelations.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                ts, "worker-7", "AUTHENTICATION_SUCCESS", "alice"));
        RequestCorrelationRegistry requestCorrelations = new RequestCorrelationRegistry(10);
        requestCorrelations.record(new RequestCorrelationRegistry.RequestCorrelation(
                BASE.plusMillis(1000).toEpochMilli(), BASE.plusMillis(1030).toEpochMilli(), "worker-7", "GET", "/a"));
        LiveActivityService service = service(
                requests(
                        exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L),
                        exchange("r2", BASE.plusMillis(2000), "GET", "/b", 200, 5L)),
                null,
                null,
                security(securityEvent("AUTHENTICATION_SUCCESS", "alice", ts)),
                null,
                requestCorrelations,
                securityCorrelations,
                null,
                new BootUiProperties());

        LiveActivityReport report = service.report(null, null, 0, 0);
        assertThat(securedPrincipalOf(report, "r1")).isEqualTo("alice");
        assertThat(securedPrincipalOf(report, "r2")).isNull();
    }

    @Test
    void doesNotMarkRequestSecuredWhenCorrelatedPrincipalIsBlank() {
        long ts = BASE.plusMillis(1015).toEpochMilli();
        SecurityEventCorrelationRegistry securityCorrelations = new SecurityEventCorrelationRegistry(10);
        securityCorrelations.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                ts, "worker-7", "AUTHORIZATION_FAILURE", ""));
        RequestCorrelationRegistry requestCorrelations = new RequestCorrelationRegistry(10);
        requestCorrelations.record(new RequestCorrelationRegistry.RequestCorrelation(
                BASE.plusMillis(1000).toEpochMilli(), BASE.plusMillis(1030).toEpochMilli(), "worker-7", "GET", "/a"));
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 403, 30L)),
                null,
                null,
                security(securityEvent("AUTHORIZATION_FAILURE", "", ts)),
                null,
                requestCorrelations,
                securityCorrelations,
                null,
                new BootUiProperties());

        // The event correlates to the request (so it still nests), but a blank principal must not flag
        // the request as authenticated.
        LiveActivityReport report = service.report(null, null, 0, 0);
        assertThat(parentOfSecurityEntry(report)).isEqualTo("r1");
        assertThat(securedPrincipalOf(report, "r1")).isNull();
    }

    @Test
    void leavesUncorrelatedSignalsTopLevel() {
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                sql(sqlEntryOn(1, BASE.plusMillis(5000).toEpochMilli(), "other-thread", "trace-zzz")),
                null,
                null,
                null,
                new BootUiProperties());

        LiveActivityReport report = service.report(null, null, 0, 0);
        assertThat(parentOf(report, "sql-1")).isNull();
        assertThat(parentOf(report, "r1")).isNull();
    }

    @Test
    void flagsRequestAsSqlNPlusOneSuspectedWhenItsCorrelatedSqlHitsTheThreshold() {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setNPlusOneThreshold(5);
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                sql(
                        sqlEntryOn(1, BASE.plusMillis(1001).toEpochMilli(), "http-thread", "trace-r1"),
                        sqlEntryOn(2, BASE.plusMillis(1002).toEpochMilli(), "http-thread", "trace-r1"),
                        sqlEntryOn(3, BASE.plusMillis(1003).toEpochMilli(), "http-thread", "trace-r1"),
                        sqlEntryOn(4, BASE.plusMillis(1004).toEpochMilli(), "http-thread", "trace-r1"),
                        sqlEntryOn(5, BASE.plusMillis(1005).toEpochMilli(), "http-thread", "trace-r1")),
                null,
                null,
                null,
                properties);

        assertThat(sqlNPlusOneSuspectedOf(service.report(null, null, 0, 0), "r1"))
                .isTrue();
    }

    @Test
    void doesNotFlagSqlNPlusOneSuspectedBelowTheThreshold() {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setNPlusOneThreshold(5);
        LiveActivityService service = service(
                requests(exchange("r1", BASE.plusMillis(1000), "GET", "/a", 200, 30L)),
                sql(
                        sqlEntryOn(1, BASE.plusMillis(1001).toEpochMilli(), "http-thread", "trace-r1"),
                        sqlEntryOn(2, BASE.plusMillis(1002).toEpochMilli(), "http-thread", "trace-r1")),
                null,
                null,
                null,
                properties);

        assertThat(sqlNPlusOneSuspectedOf(service.report(null, null, 0, 0), "r1"))
                .isFalse();
    }

    // --- helpers ---

    private static String parentOf(LiveActivityReport report, String entryId) {
        return report.entries().stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst()
                .orElseThrow()
                .parentId();
    }

    /**
     * Security entry ids are a content hash (see {@code SecurityActivityIds}), not a predictable literal,
     * so tests locate the single security entry by type instead of hardcoding an id.
     */
    private static String parentOfSecurityEntry(LiveActivityReport report) {
        return report.entries().stream()
                .filter(e -> "SECURITY".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .parentId();
    }

    private static String securedPrincipalOf(LiveActivityReport report, String entryId) {
        return report.entries().stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst()
                .orElseThrow()
                .securedPrincipal();
    }

    private static boolean sqlNPlusOneSuspectedOf(LiveActivityReport report, String entryId) {
        return report.entries().stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst()
                .orElseThrow()
                .sqlNPlusOneSuspected();
    }

    private LiveActivityService service(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            SecurityLogsController security,
            HealthController health,
            BootUiProperties properties) {
        return service(requests, sql, exceptions, security, health, null, null, null, properties);
    }

    private LiveActivityService service(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            SecurityLogsController security,
            HealthController health,
            RequestCorrelationRegistry requestCorrelations,
            SecurityEventCorrelationRegistry securityCorrelations,
            io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore scheduledTaskRuns,
            BootUiProperties properties) {
        return new LiveActivityService(
                provider(requests),
                provider(sql),
                provider(exceptions),
                provider(security),
                provider(health),
                provider(requestCorrelations),
                provider(securityCorrelations),
                provider(scheduledTaskRuns),
                properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static HttpExchangesController requests(HttpExchangeDto... exchanges) {
        HttpExchangesController controller = mock(HttpExchangesController.class);
        HttpExchangesReport report = new HttpExchangesReport(
                exchanges.length,
                exchanges.length,
                0,
                List.of(exchanges),
                new PageMetadata(0, exchanges.length, exchanges.length, 1, 0, false),
                null);
        when(controller.exchanges(null, null, null, 0, 200)).thenReturn(report);
        return controller;
    }

    private static SqlTraceController sql(SqlTraceEntryDto... entries) {
        SqlTraceController controller = mock(SqlTraceController.class);
        SqlTraceReport report = new SqlTraceReport(
                true,
                null,
                true,
                false,
                100,
                entries.length,
                1000,
                List.of("ds"),
                SqlTraceStatsDto.empty(),
                List.of(entries),
                List.of(),
                List.of());
        when(controller.trace()).thenReturn(report);
        return controller;
    }

    private static ExceptionsController exceptions(ExceptionGroupDto... groups) {
        ExceptionsController controller = mock(ExceptionsController.class);
        ExceptionsReport report = new ExceptionsReport(true, null, 50, groups.length, List.of(groups));
        when(controller.list()).thenReturn(report);
        return controller;
    }

    private static HttpExchangeDto exchange(
            String id, Instant timestamp, String method, String path, int status, Long durationMs) {
        return new HttpExchangeDto(
                id,
                timestamp,
                method,
                path,
                null,
                path,
                status,
                status >= 500 ? "SERVER_ERROR" : "SUCCESS",
                durationMs,
                null,
                null,
                null,
                null,
                "trace-" + id,
                List.of(),
                List.of());
    }

    private static SqlTraceEntryDto sqlEntry(
            long id, long timestamp, String sql, String category, long durationMillis, boolean success, boolean slow) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                sql,
                category,
                category,
                durationMillis,
                success,
                null,
                null,
                0,
                "conn-1",
                "http-thread",
                slow,
                List.of(),
                null,
                null);
    }

    private static SqlTraceEntryDto sqlEntryOn(long id, long timestamp, String thread, String traceId) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                "SELECT 1",
                "SELECT",
                "SELECT",
                5,
                true,
                null,
                null,
                0,
                "conn-1",
                thread,
                false,
                List.of(),
                traceId,
                null);
    }

    private static SecurityLogsController security(SecurityLogEventDto... events) {
        SecurityLogsController controller = mock(SecurityLogsController.class);
        SecurityLogsReport report = new SecurityLogsReport(
                true,
                null,
                100,
                List.of(),
                List.of(events),
                new PageMetadata(0, events.length, events.length, 1, 0, false));
        when(controller.logs(null, null, null, 0, 200)).thenReturn(report);
        return controller;
    }

    private static SecurityLogEventDto securityEvent(String type, String principal, long timestampMillis) {
        return new SecurityLogEventDto(
                Instant.ofEpochMilli(timestampMillis).toString(), principal, type, List.of(), null);
    }

    private static ExceptionGroupDto group(String id, String className, long lastSeen) {
        return new ExceptionGroupDto(
                id,
                className,
                "boom",
                1,
                lastSeen,
                lastSeen,
                "Foo.java:1",
                true,
                "http-thread",
                "GET",
                "/a",
                "h",
                "s",
                null,
                "OPEN",
                0);
    }
}
