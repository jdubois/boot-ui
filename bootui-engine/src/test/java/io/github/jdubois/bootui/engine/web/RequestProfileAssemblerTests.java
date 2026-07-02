package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSecurityDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the reduced, trace-id-only per-request profile the Quarkus adapter serves at
 * {@code GET /bootui/api/activity/request/{id}}: a request with a resolvable trace id gathers every
 * SQL/exception/security signal sharing that exact trace id (never a time-window or thread heuristic, unlike
 * Spring); a request with no trace id, or one that is ambiguous (shared by another captured request), never
 * fabricates a correlation.
 */
class RequestProfileAssemblerTests {

    private final RequestProfileAssembler assembler = new RequestProfileAssembler();

    @Test
    void unavailableWhenRequestNotFound() {
        RequestProfileDto profile =
                assembler.profile("missing-1", null, List.of(), List.of(), List.of(), List.of(), null);

        assertThat(profile.available()).isFalse();
        assertThat(profile.unavailableReason()).isEqualTo("Request missing-1 is no longer in the buffer");
    }

    @Test
    void unavailableWhenRequestHasNoTraceId() {
        HttpExchangeDto request = request("req-1", "/orders", null, null, 1_000L, 50L);

        RequestProfileDto profile =
                assembler.profile("req-1", request, List.of(request), List.of(), List.of(), List.of(), null);

        assertThat(profile.available()).isFalse();
        assertThat(profile.unavailableReason()).contains("No distributed trace id was captured");
        assertThat(profile.unavailableReason()).contains("quarkus-opentelemetry");
    }

    @Test
    void correlatesSqlExceptionAndSecurityExactlyByTraceId() {
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", "alice", 1_000L, 50L);
        List<HttpExchangeDto> allExchanges = List.of(request);
        List<SqlTraceEntryDto> sql =
                List.of(sql(1, "select 1", "trace-a", 10L, 1_010L), sql(2, "select 2", "trace-b", 5L, 1_020L));
        List<ExceptionDetailDto> exceptions = List.of(exceptionDetail("g-1", "trace-a", 1_030L));
        List<SecurityLogEventDto> security = List.of(
                security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_040L),
                security("bob", "AUTHENTICATION_SUCCESS", "trace-b", 1_050L));

        RequestProfileDto profile = assembler.profile("req-1", request, allExchanges, sql, exceptions, security, null);

        assertThat(profile.available()).isTrue();
        assertThat(profile.unavailableReason()).isNull();
        assertThat(profile.request()).isEqualTo(request);
        assertThat(profile.sqlCorrelationApproximate()).isFalse();

        assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L);
        assertThat(profile.exceptions()).hasSize(1);
        assertThat(profile.exceptions().get(0).exceptionClassName()).isEqualTo("java.lang.IllegalStateException");

        assertThat(profile.security()).hasSize(1);
        RequestProfileSecurityDto securityDto = profile.security().get(0);
        assertThat(securityDto.principal()).isEqualTo("alice");
        assertThat(securityDto.principalMatched()).isTrue();
        assertThat(securityDto.threadMatched()).isFalse();

        assertThat(profile.notes())
                .anyMatch(note -> note.contains("reduced, trace-id-only"))
                .anyMatch(note -> note.contains("SQL is correlated exactly by trace id trace-a"))
                .anyMatch(note -> note.contains("Exceptions are correlated exactly by trace id trace-a"))
                .anyMatch(note -> note.contains("Security events are correlated exactly by trace id trace-a"));
    }

    @Test
    void skipsCorrelationWhenTheRequestsTraceIdIsAmbiguous() {
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
        HttpExchangeDto other = request("req-2", "/items", "trace-a", null, 2_000L, 50L);
        List<SqlTraceEntryDto> sql = List.of(sql(1, "select 1", "trace-a", 10L, 1_010L));

        RequestProfileDto profile =
                assembler.profile("req-1", request, List.of(request, other), sql, List.of(), List.of(), null);

        assertThat(profile.available()).isTrue();
        assertThat(profile.sql()).isEmpty();
        assertThat(profile.notes()).anyMatch(note -> note.contains("shared by more than one captured request"));
    }

    @Test
    void groupsRepeatedSelectsAsPotentialNPlusOne() {
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 5L, 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 5L, 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 5L, 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 5L, 1_004L),
                sql(5, "select * from item where order_id = ?", "trace-a", 5L, 1_005L));

        RequestProfileDto profile =
                assembler.profile("req-1", request, List.of(request), sql, List.of(), List.of(), null);

        assertThat(profile.sqlGroups()).hasSize(1);
        assertThat(profile.sqlGroups().get(0).executions()).isEqualTo(5L);
        assertThat(profile.sqlGroups().get(0).potentialNPlusOne()).isTrue();

        assertThat(profile.timing().sqlCount()).isEqualTo(5);
        assertThat(profile.timing().sqlMs()).isEqualTo(25L);
        assertThat(profile.timing().totalMs()).isEqualTo(500L);
        assertThat(profile.timing().sqlPercent()).isEqualTo(5.0);
    }

    @Test
    void resolvesTraceOnlyWhenItsIdMatchesTheRequest() {
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
        TraceDetailDto matchingTrace = new TraceDetailDto("trace-a", List.of());
        TraceDetailDto mismatchedTrace = new TraceDetailDto("trace-other", List.of());

        RequestProfileDto matched =
                assembler.profile("req-1", request, List.of(request), List.of(), List.of(), List.of(), matchingTrace);
        RequestProfileDto mismatched =
                assembler.profile("req-1", request, List.of(request), List.of(), List.of(), List.of(), mismatchedTrace);

        assertThat(matched.trace()).isEqualTo(matchingTrace);
        assertThat(matched.notes()).anyMatch(note -> note.contains("Trace matched by id trace-a"));
        assertThat(mismatched.trace()).isNull();
    }

    private static HttpExchangeDto request(
            String id, String path, String traceId, String principal, long epochMillis, long durationMs) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                path,
                null,
                "http://localhost:8080" + path,
                200,
                "2xx",
                durationMs,
                34L,
                "127.0.0.1",
                principal,
                null,
                traceId,
                List.of(),
                List.of());
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long durationMillis, long timestamp) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                sql,
                "PREPARED",
                "SELECT",
                durationMillis,
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

    private static SecurityLogEventDto security(String principal, String type, String traceId, long epochMillis) {
        return new SecurityLogEventDto(
                Instant.ofEpochMilli(epochMillis).toString(), principal, type, List.of(), traceId);
    }

    private static ExceptionDetailDto exceptionDetail(String id, String traceId, long timestamp) {
        ExceptionGroupDto group = new ExceptionGroupDto(
                id,
                "java.lang.IllegalStateException",
                "boom",
                1,
                timestamp,
                timestamp,
                "Foo.java:1",
                true,
                "worker-1",
                "GET",
                "/orders",
                "Handler#x",
                "web",
                traceId,
                "OPEN",
                0);
        ExceptionOccurrenceDto occurrence =
                new ExceptionOccurrenceDto(timestamp, "worker-1", "GET", "/orders", "Handler#x", "web", traceId);
        return new ExceptionDetailDto(group, List.of(), List.of(), List.of(occurrence));
    }
}
