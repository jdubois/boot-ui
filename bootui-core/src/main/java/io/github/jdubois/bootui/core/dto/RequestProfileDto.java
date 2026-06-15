package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Symfony-style per-request profile: a single request correlated with the other signals it produced.
 *
 * <p>Assembled by joining BootUI's existing in-memory buffers around one HTTP exchange, strongest
 * key first: matching trace id for the distributed trace, request method + path within the request
 * time window for exceptions, time window for SQL, and time window plus principal for security audit
 * events. The SQL association is heuristic (SQL executions carry no trace id), so
 * {@link #sqlCorrelationApproximate()} flags when it was used and the UI labels it as approximate.</p>
 *
 * @param available whether the request could be located and profiled
 * @param unavailableReason why the profile is unavailable, or {@code null}
 * @param request the request summary (reused, already-masked HTTP exchange)
 * @param sql the correlated SQL executions in execution order
 * @param sqlGroups grouped SQL statements with N+1 candidates flagged
 * @param sqlCorrelationApproximate whether the SQL association relied on the thread/time heuristic
 * @param exceptions exceptions correlated to this request
 * @param security security audit events correlated to this request by time window (and principal)
 * @param trace the distributed trace for this request when a trace id matched, or {@code null}
 * @param timing coarse timing breakdown
 * @param notes human-readable notes about how correlation was performed and its caveats
 */
public record RequestProfileDto(
        boolean available,
        String unavailableReason,
        HttpExchangeDto request,
        List<SqlTraceEntryDto> sql,
        List<SqlTraceGroupDto> sqlGroups,
        boolean sqlCorrelationApproximate,
        List<RequestProfileExceptionDto> exceptions,
        List<RequestProfileSecurityDto> security,
        TraceDetailDto trace,
        RequestProfileTimingDto timing,
        List<String> notes) {

    public RequestProfileDto {
        sql = sql == null ? List.of() : List.copyOf(sql);
        sqlGroups = sqlGroups == null ? List.of() : List.copyOf(sqlGroups);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        security = security == null ? List.of() : List.copyOf(security);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static RequestProfileDto unavailable(String reason) {
        return new RequestProfileDto(
                false, reason, null, List.of(), List.of(), false, List.of(), List.of(), null, null, List.of());
    }
}
