package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LiveActivityCorrelatorTests {

    private static final Instant BASE = Instant.parse("2026-06-14T10:00:00Z");
    private static final long START = BASE.toEpochMilli();

    @Test
    void returnsUnavailableWhenRequestNotFound() {
        LiveActivityCorrelator correlator = correlator(requestsController(), null, null, null, new BootUiProperties());

        RequestProfileDto profile = correlator.profile("missing");

        assertThat(profile.available()).isFalse();
        assertThat(profile.unavailableReason()).contains("missing");
    }

    @Test
    void correlatesSqlExactlyByTraceIdWhenPresent() {
        SqlTraceController sql = sqlController(
                sqlEntry(1, START + 5, "SELECT * FROM t", "SELECT", 3, "trace-abc"),
                sqlEntry(2, START + 9000, "SELECT * FROM t", "SELECT", 3, "trace-abc"), // outside window, same trace
                sqlEntry(3, START + 6, "SELECT * FROM other", "SELECT", 3, "trace-zzz")); // in window, other trace
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L, "trace-abc")),
                sql,
                null,
                null,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.available()).isTrue();
        assertThat(profile.sql()).hasSize(2);
        assertThat(profile.sql())
                .allSatisfy(entry -> assertThat(entry.traceId()).isEqualTo("trace-abc"));
        assertThat(profile.sqlCorrelationApproximate()).isFalse();
    }

    @Test
    void fallsBackToTimeWindowWhenNoSqlMatchesTraceId() {
        SqlTraceController sql = sqlController(
                sqlEntry(1, START + 5, "SELECT * FROM t", "SELECT", 3, "trace-other"),
                sqlEntry(2, START + 90, "SELECT * FROM t", "SELECT", 3, null));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L, "trace-abc")),
                sql,
                null,
                null,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.sql()).hasSize(2);
        assertThat(profile.sqlCorrelationApproximate()).isTrue();
    }

    @Test
    void correlatesSqlByTimeWindowAndFlagsApproximate() {
        SqlTraceController sql = sqlController(
                sqlEntry(1, START + 5, "SELECT * FROM t", "SELECT", 3),
                sqlEntry(2, START + 90, "SELECT * FROM t", "SELECT", 3),
                sqlEntry(3, START + 5000, "SELECT * FROM other", "SELECT", 3)); // outside window
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L)),
                sql,
                null,
                null,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.available()).isTrue();
        assertThat(profile.sql()).hasSize(2);
        assertThat(profile.sqlCorrelationApproximate()).isTrue();
        assertThat(profile.timing().sqlCount()).isEqualTo(2);
        assertThat(profile.timing().sqlMs()).isEqualTo(6L);
    }

    @Test
    void detectsNPlusOneWhenIdenticalSelectsRepeatAboveThreshold() {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setNPlusOneThreshold(3);
        SqlTraceController sql = sqlController(
                sqlEntry(1, START + 1, "SELECT * FROM child WHERE id = ?", "SELECT", 1),
                sqlEntry(2, START + 2, "SELECT * FROM child WHERE id = ?", "SELECT", 1),
                sqlEntry(3, START + 3, "SELECT * FROM child WHERE id = ?", "SELECT", 1));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L)), sql, null, null, properties);

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.sqlGroups()).hasSize(1);
        assertThat(profile.sqlGroups().get(0).executions()).isEqualTo(3);
        assertThat(profile.sqlGroups().get(0).potentialNPlusOne()).isTrue();
    }

    @Test
    void doesNotFlagNPlusOneBelowThreshold() {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setNPlusOneThreshold(5);
        SqlTraceController sql = sqlController(
                sqlEntry(1, START + 1, "SELECT * FROM child WHERE id = ?", "SELECT", 1),
                sqlEntry(2, START + 2, "SELECT * FROM child WHERE id = ?", "SELECT", 1));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L)), sql, null, null, properties);

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.sqlGroups().get(0).potentialNPlusOne()).isFalse();
    }

    @Test
    void correlatesExceptionsByPathMethodAndWindow() {
        ExceptionsController exceptions = exceptionsController(
                "GET",
                "/a",
                START + 10, // matches
                "POST",
                "/a",
                START + 10, // wrong method
                "GET",
                "/b",
                START + 10); // wrong path
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 500, 100L)),
                null,
                exceptions,
                null,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.exceptions()).hasSize(1);
        assertThat(profile.exceptions().get(0).exceptionClassName()).isEqualTo("ex-GET-/a");
    }

    @Test
    void correlatesExceptionsExactlyByServingThreadAcrossConcurrentIdenticalRequests() {
        // Two concurrent GET /a requests both threw the same exception in the same window, on
        // different worker threads. The registry uniquely identifies this exchange's thread, so only
        // the occurrence thrown on that thread is attributed to it.
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(100);
        registry.record(new RequestCorrelationRegistry.RequestCorrelation(START, START + 100, "exec-1", "GET", "/a"));

        ExceptionsController exceptions = exceptionsControllerWithOccurrences(
                "GET",
                "/a",
                new ExceptionOccurrenceDto(START + 10, "exec-1", "GET", "/a", "h", "web", null),
                new ExceptionOccurrenceDto(START + 20, "exec-2", "GET", "/a", "h", "web", null));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 500, 100L)),
                null,
                exceptions,
                null,
                null,
                registry,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.exceptions()).extracting(dto -> dto.thread()).containsExactly("exec-1");
        assertThat(profile.notes()).anyMatch(note -> note.contains("serving thread"));
    }

    @Test
    void fallsBackToMethodPathWindowForExceptionsWhenServingThreadIsAmbiguous() {
        // Two genuinely concurrent identical requests overlap, so the request's serving thread cannot
        // be uniquely identified and exception correlation stays on method + path + window.
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(100);
        registry.record(new RequestCorrelationRegistry.RequestCorrelation(START, START + 100, "exec-1", "GET", "/a"));
        registry.record(
                new RequestCorrelationRegistry.RequestCorrelation(START + 10, START + 90, "exec-2", "GET", "/a"));

        ExceptionsController exceptions = exceptionsControllerWithOccurrences(
                "GET",
                "/a",
                new ExceptionOccurrenceDto(START + 10, "exec-1", "GET", "/a", "h", "web", null),
                new ExceptionOccurrenceDto(START + 20, "exec-2", "GET", "/a", "h", "web", null));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 500, 100L)),
                null,
                exceptions,
                null,
                null,
                registry,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.exceptions()).extracting(dto -> dto.thread()).containsExactly("exec-1", "exec-2");
        assertThat(profile.notes()).noneMatch(note -> note.contains("serving thread"));
    }

    @Test
    void correlatesSecurityEventsByTimeWindowAndPrincipal() {
        SecurityLogsController security = securityController(
                securityEvent("AUTHENTICATION_SUCCESS", "admin", START + 5), // in window, principal matches
                securityEvent("AUTHORIZATION_FAILURE", "bob", START + 6), // in window, different principal
                securityEvent("LOGOUT_SUCCESS", null, START + 7), // in window, no principal -> kept by time
                securityEvent("AUTHENTICATION_SUCCESS", "admin", START + 9000)); // outside window
        LiveActivityCorrelator correlator = correlator(
                requestsController(secureExchange("r1", BASE, "admin")),
                null,
                null,
                null,
                security,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.security()).hasSize(2);
        assertThat(profile.security())
                .extracting(dto -> dto.type())
                .containsExactly("AUTHENTICATION_SUCCESS", "LOGOUT_SUCCESS");
        assertThat(profile.security().get(0).principalMatched()).isTrue();
        assertThat(profile.security().get(1).principalMatched()).isFalse();
        assertThat(profile.notes()).anyMatch(note -> note.contains("Security events are matched"));
    }

    @Test
    void correlatesSecurityExactlyByServingThreadAcrossConcurrentRequestsSharingPrincipal() {
        // Two concurrent admin requests both raise AUTHORIZATION_FAILURE in the same window; only the
        // event emitted on this request's serving thread (exec-1) belongs to it.
        SecurityLogsController security = securityController(
                securityEvent("AUTHORIZATION_FAILURE", "admin", START + 5),
                securityEvent("AUTHORIZATION_FAILURE", "admin", START + 40));

        RequestCorrelationRegistry requests = new RequestCorrelationRegistry(100);
        requests.record(new RequestCorrelationRegistry.RequestCorrelation(
                START, START + 100, "exec-1", "GET", "/secure/admin"));

        SecurityEventCorrelationRegistry events = new SecurityEventCorrelationRegistry(100);
        events.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                START + 5, "exec-1", "AUTHORIZATION_FAILURE", "admin"));
        events.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                START + 40, "exec-2", "AUTHORIZATION_FAILURE", "admin"));

        LiveActivityCorrelator correlator = correlator(
                requestsController(secureExchange("r1", BASE, "admin")),
                null,
                null,
                null,
                security,
                requests,
                events,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.security()).hasSize(1);
        assertThat(profile.security().get(0).timestamp()).isEqualTo(START + 5);
        assertThat(profile.security().get(0).threadMatched()).isTrue();
        assertThat(profile.notes()).anyMatch(note -> note.contains("serving thread"));
    }

    @Test
    void keepsSecurityEventsWhenServingThreadKnownButEventNotCaptured() {
        // The serving thread is known but the security registry did not retain the event (UNKNOWN): keep
        // it via the time-window match rather than dropping a real event.
        SecurityLogsController security =
                securityController(securityEvent("AUTHENTICATION_SUCCESS", "admin", START + 5));

        RequestCorrelationRegistry requests = new RequestCorrelationRegistry(100);
        requests.record(new RequestCorrelationRegistry.RequestCorrelation(
                START, START + 100, "exec-1", "GET", "/secure/admin"));

        SecurityEventCorrelationRegistry events = new SecurityEventCorrelationRegistry(100); // nothing captured

        LiveActivityCorrelator correlator = correlator(
                requestsController(secureExchange("r1", BASE, "admin")),
                null,
                null,
                null,
                security,
                requests,
                events,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.security()).hasSize(1);
        assertThat(profile.security().get(0).threadMatched()).isFalse();
    }

    @Test
    void correlatesSqlExactlyByServingThreadWhenNoTraceId() {
        // The request was handled on thread "exec-1" during [START, START+100].
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(100);
        registry.record(new RequestCorrelationRegistry.RequestCorrelation(START, START + 100, "exec-1", "GET", "/a"));

        SqlTraceController sql = sqlController(
                sqlEntryOnThread(1, START + 10, "SELECT * FROM t", "SELECT", 3, "exec-1"), // same thread, in window
                sqlEntryOnThread(2, START + 20, "SELECT * FROM u", "SELECT", 4, "exec-2"), // concurrent other request
                sqlEntryOnThread(3, START + 50, "SELECT * FROM v", "SELECT", 5, "exec-1")); // same thread, in window
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L)),
                sql,
                null,
                null,
                null,
                registry,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L, 3L);
        assertThat(profile.sqlCorrelationApproximate()).isFalse();
        assertThat(profile.notes()).anyMatch(note -> note.contains("serving thread"));
    }

    @Test
    void fallsBackToApproximateWhenServingThreadIsAmbiguous() {
        // Two genuinely concurrent identical requests have overlapping windows, so the serving thread
        // cannot be uniquely identified and correlation must stay on the time-window heuristic.
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(100);
        registry.record(new RequestCorrelationRegistry.RequestCorrelation(START, START + 100, "exec-1", "GET", "/a"));
        registry.record(
                new RequestCorrelationRegistry.RequestCorrelation(START + 10, START + 90, "exec-2", "GET", "/a"));

        SqlTraceController sql = sqlController(
                sqlEntryOnThread(1, START + 10, "SELECT 1", "SELECT", 3, "exec-1"),
                sqlEntryOnThread(2, START + 20, "SELECT 2", "SELECT", 4, "exec-2"));
        LiveActivityCorrelator correlator = correlator(
                requestsController(exchange("r1", BASE, "GET", "/a", 200, 100L)),
                sql,
                null,
                null,
                null,
                registry,
                new BootUiProperties());

        RequestProfileDto profile = correlator.profile("r1");

        assertThat(profile.sql()).hasSize(2);
        assertThat(profile.sqlCorrelationApproximate()).isTrue();
    }

    // --- helpers ---

    private LiveActivityCorrelator correlator(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            TracesController traces,
            BootUiProperties properties) {
        return correlator(requests, sql, exceptions, traces, null, properties);
    }

    private LiveActivityCorrelator correlator(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            TracesController traces,
            SecurityLogsController security,
            BootUiProperties properties) {
        return correlator(requests, sql, exceptions, traces, security, null, properties);
    }

    private LiveActivityCorrelator correlator(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            TracesController traces,
            SecurityLogsController security,
            RequestCorrelationRegistry requestCorrelations,
            BootUiProperties properties) {
        return correlator(requests, sql, exceptions, traces, security, requestCorrelations, null, properties);
    }

    private LiveActivityCorrelator correlator(
            HttpExchangesController requests,
            SqlTraceController sql,
            ExceptionsController exceptions,
            TracesController traces,
            SecurityLogsController security,
            RequestCorrelationRegistry requestCorrelations,
            SecurityEventCorrelationRegistry securityCorrelations,
            BootUiProperties properties) {
        return new LiveActivityCorrelator(
                provider(requests),
                provider(sql),
                provider(exceptions),
                provider(security),
                provider(traces),
                provider(requestCorrelations),
                provider(securityCorrelations),
                properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static HttpExchangesController requestsController(HttpExchangeDto... exchanges) {
        HttpExchangesController controller = mock(HttpExchangesController.class);
        HttpExchangesReport report = new HttpExchangesReport(
                exchanges.length,
                exchanges.length,
                0,
                List.of(exchanges),
                new PageMetadata(0, exchanges.length, exchanges.length, 1, 0, false),
                null);
        when(controller.exchanges(eq(null), eq(null), eq(null), eq(0), anyInt()))
                .thenReturn(report);
        return controller;
    }

    private static SecurityLogsController securityController(SecurityLogEventDto... events) {
        SecurityLogsController controller = mock(SecurityLogsController.class);
        SecurityLogsReport report = new SecurityLogsReport(
                true,
                null,
                1000,
                List.of(),
                List.of(events),
                new PageMetadata(0, events.length, events.length, 1, 0, false));
        when(controller.logs(any(), any(), any(), any(), any())).thenReturn(report);
        return controller;
    }

    private static SecurityLogEventDto securityEvent(String type, String principal, long epochMillis) {
        return new SecurityLogEventDto(Instant.ofEpochMilli(epochMillis).toString(), principal, type, List.of(), null);
    }

    private static SqlTraceController sqlController(SqlTraceEntryDto... entries) {
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

    private static ExceptionsController exceptionsController(Object... methodPathTimeTriples) {
        ExceptionsController controller = mock(ExceptionsController.class);
        int count = methodPathTimeTriples.length / 3;
        ExceptionGroupDto[] groups = new ExceptionGroupDto[count];
        for (int i = 0; i < count; i++) {
            String method = (String) methodPathTimeTriples[i * 3];
            String path = (String) methodPathTimeTriples[i * 3 + 1];
            long ts = (long) methodPathTimeTriples[i * 3 + 2];
            String id = "g" + i;
            groups[i] = new ExceptionGroupDto(
                    id,
                    "ex-" + method + "-" + path,
                    "boom",
                    1,
                    ts,
                    ts,
                    "Foo.java:1",
                    true,
                    "t",
                    method,
                    path,
                    "h",
                    "s",
                    null,
                    "OPEN",
                    0);
            ExceptionDetailDto detail = new ExceptionDetailDto(
                    groups[i],
                    List.of(),
                    List.of(),
                    List.of(new ExceptionOccurrenceDto(ts, "t", method, path, "h", "s", null)));
            when(controller.detail(id)).thenReturn(detail);
        }
        ExceptionsReport report = new ExceptionsReport(true, null, 50, count, List.of(groups));
        when(controller.list()).thenReturn(report);
        return controller;
    }

    private static ExceptionsController exceptionsControllerWithOccurrences(
            String method, String path, ExceptionOccurrenceDto... occurrences) {
        ExceptionsController controller = mock(ExceptionsController.class);
        long firstTs = occurrences.length == 0 ? 0 : occurrences[0].timestamp();
        long lastTs = occurrences.length == 0 ? 0 : occurrences[occurrences.length - 1].timestamp();
        String lastThread = occurrences.length == 0 ? "t" : occurrences[occurrences.length - 1].thread();
        ExceptionGroupDto group = new ExceptionGroupDto(
                "g0",
                "ex-" + method + "-" + path,
                "boom",
                occurrences.length,
                firstTs,
                lastTs,
                "Foo.java:1",
                true,
                lastThread,
                method,
                path,
                "h",
                "web",
                null,
                "OPEN",
                0);
        ExceptionDetailDto detail = new ExceptionDetailDto(group, List.of(), List.of(), List.of(occurrences));
        when(controller.detail("g0")).thenReturn(detail);
        ExceptionsReport report = new ExceptionsReport(true, null, 50, 1, List.of(group));
        when(controller.list()).thenReturn(report);
        return controller;
    }

    private static HttpExchangeDto exchange(
            String id, Instant timestamp, String method, String path, int status, Long durationMs) {
        return exchange(id, timestamp, method, path, status, durationMs, null);
    }

    private static HttpExchangeDto secureExchange(String id, Instant timestamp, String principal) {
        return new HttpExchangeDto(
                id,
                timestamp,
                "GET",
                "/secure/admin",
                null,
                "/secure/admin",
                200,
                "SUCCESS",
                100L,
                null,
                null,
                principal,
                null,
                null,
                List.of(),
                List.of());
    }

    private static HttpExchangeDto exchange(
            String id, Instant timestamp, String method, String path, int status, Long durationMs, String traceId) {
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
                traceId,
                List.of(),
                List.of());
    }

    private static SqlTraceEntryDto sqlEntry(
            long id, long timestamp, String sql, String category, long durationMillis) {
        return sqlEntry(id, timestamp, sql, category, durationMillis, null);
    }

    private static SqlTraceEntryDto sqlEntryOnThread(
            long id, long timestamp, String sql, String category, long durationMillis, String thread) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                sql,
                category,
                category,
                durationMillis,
                true,
                null,
                null,
                0,
                "conn-1",
                thread,
                false,
                List.of(),
                null);
    }

    private static SqlTraceEntryDto sqlEntry(
            long id, long timestamp, String sql, String category, long durationMillis, String traceId) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                sql,
                category,
                category,
                durationMillis,
                true,
                null,
                null,
                0,
                "conn-1",
                "http-thread",
                false,
                List.of(),
                traceId);
    }
}
