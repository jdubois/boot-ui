package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileExceptionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSecurityDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTimingDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-neutral assembly of a <strong>reduced, trace-id-only</strong> Symfony-style per-request profile,
 * for adapters that have no thread-per-request identity to correlate on (Quarkus's reactive event-loop/worker
 * model, unlike Spring's synchronous servlet model).
 *
 * <p>Spring's {@code LiveActivityCorrelator} joins a request to its signals with a tiered strategy —
 * matching trace id first, falling back to HTTP method+path+time-window+thread heuristics for exceptions,
 * serving-thread correlation for SQL, and time-window+principal for security events. Tiers 2-4 rely on a
 * servlet thread serving exactly one request at a time, an invariant the Vert.x event loop does not provide,
 * so they have no reliable Quarkus equivalent and are deliberately <strong>not</strong> ported here. This
 * assembler implements tier 1 only: when (and only when) a request carries a distributed-trace id (stamped
 * via {@code Span.current()} when {@code quarkus-opentelemetry} is present — the same mechanism
 * {@link LiveActivityAssembler} uses to nest entries in the main feed), every SQL execution, exception
 * occurrence, and security event sharing that exact trace id is gathered into the profile. There is no
 * approximate/heuristic fallback: a request with no trace id (or a shared, ambiguous one — see
 * {@link TraceCorrelationIndex}) simply renders {@link RequestProfileDto#unavailable(String)} or an empty,
 * honestly-labeled correlation, never fabricated or guessed data.</p>
 *
 * <p>Reuses {@link TraceCorrelationIndex} — the exact same trace-id→request primitive
 * {@link LiveActivityAssembler} uses for the main feed — so a request whose trace id is shared by more than
 * one captured exchange safely correlates nothing rather than risking attributing another request's signals
 * to this one.</p>
 */
public final class RequestProfileAssembler {

    /**
     * Builds the profile for {@code request}, or an honest {@link RequestProfileDto#unavailable(String)}
     * when it can't be found or carries no trace id to correlate on.
     *
     * @param requestId the requested exchange id, used only to compose a precise not-found message
     * @param request the resolved HTTP exchange to profile, or {@code null} when {@code requestId} is no
     *     longer in the buffer
     * @param allExchanges every currently-captured HTTP exchange (including {@code request} itself), used to
     *     build the {@link TraceCorrelationIndex} ambiguity guard; may be {@code null}
     * @param sqlEntries already-masked SQL trace executions to search for a matching trace id, or
     *     {@code null} when the SQL trace source is unavailable
     * @param exceptionDetails full exception details (group + occurrences) to search for occurrences
     *     sharing the request's trace id, or {@code null} when the exceptions source is unavailable
     * @param securityEvents already-masked security/audit events to search for a matching trace id, or
     *     {@code null} when the security-log source is unavailable
     * @param trace the distributed trace detail for the request's trace id, when cheaply available, or
     *     {@code null}
     */
    public RequestProfileDto profile(
            String requestId,
            HttpExchangeDto request,
            List<HttpExchangeDto> allExchanges,
            List<SqlTraceEntryDto> sqlEntries,
            List<ExceptionDetailDto> exceptionDetails,
            List<SecurityLogEventDto> securityEvents,
            TraceDetailDto trace) {
        if (request == null) {
            return RequestProfileDto.unavailable("Request " + requestId + " is no longer in the buffer");
        }
        String traceId = blankToNull(request.traceId());
        if (traceId == null) {
            return RequestProfileDto.unavailable("No distributed trace id was captured for this request; "
                    + "per-request profiling on Quarkus currently requires quarkus-opentelemetry.");
        }

        TraceCorrelationIndex traceIndex = TraceCorrelationIndex.of(allExchanges == null ? List.of() : allExchanges);
        // Safety: if this request's own trace id turns out to be shared by another captured exchange
        // (an ambiguous, likely reused, inbound traceparent), no signal carrying it can be safely
        // attributed to *this* request rather than the other one, so correlation is skipped entirely —
        // the profile still renders (available: true) but with empty, honestly-noted lists rather than
        // risking a cross-request data leak.
        boolean owned = request.id().equals(traceIndex.parentRequestId(traceId));

        List<String> notes = new ArrayList<>();
        notes.add("This is a reduced, trace-id-only profile: unlike Spring's profiler, Quarkus has no "
                + "time-window or serving-thread correlation, so only signals sharing this exact trace id "
                + "are shown.");
        if (!owned) {
            notes.add("This request's trace id " + traceId + " is shared by more than one captured request, "
                    + "so correlation was skipped to avoid attributing another request's signals to this one.");
        }

        List<SqlTraceEntryDto> sql = owned ? matchingSql(traceId, sqlEntries) : List.of();
        if (!sql.isEmpty()) {
            notes.add("SQL is correlated exactly by trace id " + traceId + ".");
        }
        List<SqlTraceGroupDto> sqlGroups = groupSql(sql);

        List<RequestProfileExceptionDto> exceptions = owned ? matchingExceptions(traceId, exceptionDetails) : List.of();
        if (!exceptions.isEmpty()) {
            notes.add("Exceptions are correlated exactly by trace id " + traceId + ".");
        }

        List<RequestProfileSecurityDto> security =
                owned ? matchingSecurity(traceId, request.principal(), securityEvents) : List.of();
        if (!security.isEmpty()) {
            notes.add("Security events are correlated exactly by trace id " + traceId + " (Quarkus has no "
                    + "per-request serving-thread identity to further disambiguate them, unlike Spring).");
        }

        TraceDetailDto matchedTrace = trace != null && traceId.equals(trace.traceId()) ? trace : null;
        if (matchedTrace != null) {
            notes.add("Trace matched by id " + traceId + ".");
        }

        long sqlMs = sql.stream().mapToLong(SqlTraceEntryDto::durationMillis).sum();
        Double sqlPercent = (request.durationMs() != null && request.durationMs() > 0)
                ? Math.round(10000.0 * sqlMs / request.durationMs()) / 100.0
                : null;
        RequestProfileTimingDto timing =
                new RequestProfileTimingDto(request.durationMs(), sqlMs, sql.size(), sqlPercent);

        return new RequestProfileDto(
                true, null, request, sql, sqlGroups, false, exceptions, security, matchedTrace, timing, notes);
    }

    private static List<SqlTraceEntryDto> matchingSql(String traceId, List<SqlTraceEntryDto> sqlEntries) {
        if (sqlEntries == null) {
            return List.of();
        }
        List<SqlTraceEntryDto> matched = new ArrayList<>();
        for (SqlTraceEntryDto entry : sqlEntries) {
            if (traceId.equals(entry.traceId())) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator.comparingLong(SqlTraceEntryDto::timestamp));
        return matched;
    }

    /**
     * Matches exception occurrences (not just each group's single {@code lastTraceId}) so an exception
     * group with occurrences from several different requests still correlates every occurrence that shares
     * this request's trace id, not only its most recent one.
     */
    private static List<RequestProfileExceptionDto> matchingExceptions(
            String traceId, List<ExceptionDetailDto> exceptionDetails) {
        if (exceptionDetails == null) {
            return List.of();
        }
        List<RequestProfileExceptionDto> matched = new ArrayList<>();
        for (ExceptionDetailDto detail : exceptionDetails) {
            ExceptionGroupDto group = detail.group();
            for (ExceptionOccurrenceDto occurrence : detail.occurrences()) {
                if (traceId.equals(occurrence.traceId())) {
                    matched.add(new RequestProfileExceptionDto(
                            group.exceptionClassName(),
                            group.message(),
                            group.location(),
                            occurrence.timestamp(),
                            occurrence.thread(),
                            occurrence.handler(),
                            occurrence.source()));
                }
            }
        }
        matched.sort(Comparator.comparingLong(RequestProfileExceptionDto::timestamp));
        return matched;
    }

    private static List<RequestProfileSecurityDto> matchingSecurity(
            String traceId, String requestPrincipal, List<SecurityLogEventDto> securityEvents) {
        if (securityEvents == null) {
            return List.of();
        }
        List<RequestProfileSecurityDto> matched = new ArrayList<>();
        for (SecurityLogEventDto event : securityEvents) {
            if (traceId.equals(event.traceId())) {
                boolean principalMatched = principalMatches(requestPrincipal, event.principal());
                // No thread-identity concept exists on Quarkus's reactive model, so this reduced profile
                // never claims a serving-thread match; trace id is already the exact correlation key.
                matched.add(new RequestProfileSecurityDto(
                        event.type(), event.principal(), parseEpochMillis(event.timestamp()), principalMatched, false));
            }
        }
        matched.sort(Comparator.comparingLong(RequestProfileSecurityDto::timestamp));
        return matched;
    }

    private static boolean principalMatches(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /**
     * Group correlated SQL statements by normalized text for N+1 detection, delegating to the same
     * {@link SqlTraceGrouping} helper the list-level Live Activity badge and the global SQL Trace panel
     * use, so all three agree on exactly what counts as a potential N+1 (Quarkus has no equivalent
     * {@code bootui.activity.n-plus-one-threshold} configuration property yet, hence the fixed default).
     */
    private static List<SqlTraceGroupDto> groupSql(List<SqlTraceEntryDto> sql) {
        return SqlTraceGrouping.group(sql, SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Parse an ISO-8601 instant to epoch millis, returning {@code 0} for null/blank/unparseable input. */
    private static long parseEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0L;
        }
    }
}
