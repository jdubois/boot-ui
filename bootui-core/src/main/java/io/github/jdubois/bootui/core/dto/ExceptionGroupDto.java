package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one grouped exception for the Exceptions panel list and live stream.
 *
 * <p>Exceptions are grouped by a stable {@code id} (a fingerprint of the exception type and the
 * top stack frames) so repeated failures collapse into a single row with an occurrence
 * {@code count}. The {@code last*} fields describe the most recent occurrence. {@code message} is
 * already masked according to the configured value-exposure policy.</p>
 *
 * <p>{@code lastTraceId} is the distributed-trace id of the most recent occurrence, or {@code null} when
 * none was captured. It lets the Live Activity timeline nest this exception under the HTTP request that
 * shares the same trace id — populated on Quarkus and Spring WebFlux (both stamp it from a
 * {@code TraceIdProvider} at the exception-capture point); {@code null} on Spring servlet (MVC), which has
 * no such provider wired at its {@code HandlerExceptionResolver} capture point and instead correlates by
 * serving thread (see {@code ActivityEntryDto.parentId}).</p>
 *
 * <p>{@code status} is one of {@code OPEN} (default), {@code ACKNOWLEDGED}, or {@code RESOLVED} — the
 * triage workflow for this group. {@code regressionCount} counts how many times a {@code RESOLVED}
 * group has automatically reopened to {@code OPEN} after a new occurrence arrived (a Sentry-style
 * regression signal); it is {@code 0} if the group has never regressed.</p>
 */
public record ExceptionGroupDto(
        String id,
        String exceptionClassName,
        String message,
        long count,
        long firstSeen,
        long lastSeen,
        String location,
        boolean applicationException,
        String lastThread,
        String lastRequestMethod,
        String lastRequestPath,
        String lastHandler,
        String lastSource,
        String lastTraceId,
        String status,
        long regressionCount) {}
