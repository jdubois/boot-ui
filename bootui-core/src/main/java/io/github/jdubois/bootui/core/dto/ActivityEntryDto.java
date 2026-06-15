package io.github.jdubois.bootui.core.dto;

/**
 * A single normalized entry in the Live Activity stream.
 *
 * <p>Entries are produced by merging BootUI's existing in-memory signal buffers (HTTP exchanges,
 * SQL trace, exceptions, security logs) into one chronological feed. Each source is consumed
 * through its own controller, so values are already masked and self-filtered before they reach
 * this shape.</p>
 *
 * @param id stable identifier for the entry; for {@code REQUEST} entries this is the HTTP exchange
 *     id, which the per-request profiler endpoint accepts as {@code /activity/request/{id}}
 * @param type coarse activity type: {@code REQUEST}, {@code SQL}, {@code EXCEPTION}, or {@code SECURITY}
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
 *     under that request), or {@code null} when the entry has no precise request correlation
 * @param securedPrincipal for a {@code REQUEST} entry that had a correlated Spring Security audit
 *     event, the principal it ran as (empty string when the principal is unknown); {@code null} when
 *     the request was not secured or for non-request entries
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
        String securedPrincipal) {}
