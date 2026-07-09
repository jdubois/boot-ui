package io.github.jdubois.bootui.core.dto;

/**
 * A single normalized entry in the Live Activity stream.
 *
 * <p>Entries are produced by merging BootUI's existing in-memory signal buffers (HTTP exchanges,
 * SQL trace, REST client trace, exceptions, security logs, cache accesses, scheduled-task executions,
 * captured emails, and Kafka messaging) into one chronological feed. Each source is consumed through
 * its own controller, so values are already masked and self-filtered before they reach this shape.</p>
 *
 * @param id stable identifier for the entry; for {@code REQUEST} entries this is the HTTP exchange
 *     id, which the per-request profiler endpoint accepts as {@code /activity/request/{id}}
 * @param type coarse activity type: {@code REQUEST}, {@code SQL}, {@code REST_CLIENT}, {@code EXCEPTION},
 *     {@code SECURITY}, {@code MAIL}, {@code CACHE}, {@code SCHEDULED_TASK}, or {@code MESSAGING}
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
 *     SCHEDULED_TASK} entry for the background execution that produced it
 * @param securedPrincipal for a {@code REQUEST} entry that ran as an authenticated principal — either
 *     from the request's own security context, or via a correlated audit/security event naming one —
 *     the principal it ran as; {@code null} when the request was not secured (no correlated event, or
 *     none with a known principal) or for non-request entries
 * @param sqlNPlusOneSuspected for a {@code REQUEST} entry, whether its correlated SQL executions contain
 *     a group that looks like an N+1 access pattern (same threshold/logic the per-request profile
 *     drawer uses); always {@code false} for non-request entries
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
        boolean sqlNPlusOneSuspected) {}
