package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single normalized entry in the Live Activity stream.
 *
 * <p>Entries are produced by merging BootUI's existing in-memory signal buffers (HTTP exchanges,
 * SQL trace, REST client trace, exceptions, security logs, cache accesses, scheduled-task executions,
 * captured emails, Kafka messaging, and fault-tolerance policy events) into one chronological feed. Each source
 * is consumed through its own controller, so values are already masked and self-filtered before they reach
 * this shape.</p>
 *
 * @param id stable identifier for the entry; for {@code REQUEST} entries this is the HTTP exchange
 *     id, which the per-request profiler endpoint accepts as {@code /activity/request/{id}}
 * @param type coarse activity type: {@code REQUEST}, {@code SQL}, {@code REST_CLIENT}, {@code EXCEPTION},
 *     {@code SECURITY}, {@code MAIL}, {@code CACHE}, {@code SCHEDULED}, {@code MESSAGING}, or
 *     {@code FAULT_TOLERANCE}
 * @param timestamp epoch milliseconds when the activity occurred
 * @param severity {@code OK}, {@code SLOW}, {@code WARN}, or {@code ERROR}
 * @param summary one-line, already-masked human-readable summary
 * @param detail optional secondary line (already masked), or {@code null}
 * @param durationMs wall-clock duration in milliseconds when known, or {@code null}
 * @param correlationId trace id (when present) used to relate entries, or {@code null}
 * @param method HTTP method for request entries, or {@code null}
 * @param path request path for request entries (never includes the query string), or {@code null}
 * @param status HTTP status for request entries, or {@code null}
 * @param thread originating thread name when known, or {@code null}
 * @param profileable whether a per-request profile can be requested for this entry
 * @param parentId id of the {@code REQUEST} entry this entry is correlated to (so the UI can nest it
 *     under that request), or {@code null} when the entry has no precise request correlation; for an
 *     {@code EXCEPTION} entry with no owning request, this may instead reference the {@code
 *     SCHEDULED} entry for the background execution that produced it
 * @param securedPrincipal for a {@code REQUEST} entry that ran as an authenticated principal — either
 *     from the request's own security context, or via a correlated audit/security event naming one —
 *     the principal it ran as; {@code null} when the request was not secured (no correlated event, or
 *     none with a known principal) or for non-request entries
 * @param sqlNPlusOneSuspected for a {@code REQUEST} entry, whether its correlated SQL executions contain
 *     a group that looks like an N+1 access pattern (same threshold/logic the per-request profile
 *     drawer uses); always {@code false} for non-request entries
 * @param badges short markers of the entry's state, such as {@code AFTER_RESPONSE}, {@code RUNNING}, or
 *     {@code CAPPED} for an {@code ASYNC} entry the BootUI agent propagated (M5-2); empty for most entries
 * @param exceptionGroupId for an {@code EXCEPTION} entry, the Exceptions panel's group id, which
 *     {@code get_exception_detail} and {@code /exceptions/{id}} accept, or {@code null} when unknown or for other
 *     entries; an entry's own {@code id} is never a group id
 */
public record ActivityEntryDto(
        String id,
        String type,
        long timestamp,
        String severity,
        String summary,
        String detail,
        Long durationMs,
        String correlationId,
        String method,
        String path,
        Integer status,
        String thread,
        boolean profileable,
        String parentId,
        String securedPrincipal,
        boolean sqlNPlusOneSuspected,
        List<String> badges,
        String exceptionGroupId) {

    /** An {@code ASYNC} entry's task was still running once its request's response started. */
    public static final String BADGE_AFTER_RESPONSE = "AFTER_RESPONSE";

    /** An {@code ASYNC} entry's task is still running. */
    public static final String BADGE_RUNNING = "RUNNING";

    /** An {@code ASYNC} entry's task ran past its handoff deadline. */
    public static final String BADGE_CAPPED = "CAPPED";

    public ActivityEntryDto {
        badges = DtoCollections.immutableCopy(badges);
    }

    /** An entry that is no exception's. */
    public ActivityEntryDto(
            String id,
            String type,
            long timestamp,
            String severity,
            String summary,
            String detail,
            Long durationMs,
            String correlationId,
            String method,
            String path,
            Integer status,
            String thread,
            boolean profileable,
            String parentId,
            String securedPrincipal,
            boolean sqlNPlusOneSuspected,
            List<String> badges) {
        this(
                id,
                type,
                timestamp,
                severity,
                summary,
                detail,
                durationMs,
                correlationId,
                method,
                path,
                status,
                thread,
                profileable,
                parentId,
                securedPrincipal,
                sqlNPlusOneSuspected,
                badges,
                null);
    }

    /** This entry with {@code exceptionGroupId}, every other field unchanged. */
    public ActivityEntryDto withExceptionGroupId(String exceptionGroupId) {
        return new ActivityEntryDto(
                id,
                type,
                timestamp,
                severity,
                summary,
                detail,
                durationMs,
                correlationId,
                method,
                path,
                status,
                thread,
                profileable,
                parentId,
                securedPrincipal,
                sqlNPlusOneSuspected,
                badges,
                exceptionGroupId);
    }

    /** An entry without badges. */
    public ActivityEntryDto(
            String id,
            String type,
            long timestamp,
            String severity,
            String summary,
            String detail,
            Long durationMs,
            String correlationId,
            String method,
            String path,
            Integer status,
            String thread,
            boolean profileable,
            String parentId,
            String securedPrincipal,
            boolean sqlNPlusOneSuspected) {
        this(
                id,
                type,
                timestamp,
                severity,
                summary,
                detail,
                durationMs,
                correlationId,
                method,
                path,
                status,
                thread,
                profileable,
                parentId,
                securedPrincipal,
                sqlNPlusOneSuspected,
                List.of());
    }
}
