package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.engine.support.BlankStrings;

/**
 * A query against an {@link ActivityStore}: an optional filter set plus a "load more" pagination
 * cursor. All filters are optional (blank/{@code null} means "no filter on this field") and are applied
 * as an AND.
 *
 * @param instanceId the BootUI instance whose rows to read (multi-tenant partition key); every
 *     implementation must scope its query to this instance and never return another instance's rows
 * @param type coarse activity type to keep ({@code REQUEST}/{@code SQL}/{@code EXCEPTION}/{@code
 *     SECURITY}), case-insensitive, or {@code null}/blank for all types
 * @param severity severity to keep ({@code OK}/{@code SLOW}/{@code WARN}/{@code ERROR}),
 *     case-insensitive, or {@code null}/blank for all severities
 * @param text free-text needle matched against summary/detail/path/method, case-insensitive, or {@code
 *     null}/blank for no text filter
 * @param since only entries strictly newer than this epoch-millis bound, or {@code null} for no lower bound
 * @param until only entries at or before this epoch-millis bound, or {@code null} for no upper bound
 * @param cursor opaque {@link ActivityCursor#encode() encoded cursor} from a previous {@link
 *     ActivityPage#nextCursor()}; entries strictly older than it are returned. {@code null} for the
 *     first (newest) page
 * @param pageSize maximum entries to return; clamped to at least 1
 */
public record ActivityQuery(
        String instanceId,
        String type,
        String severity,
        String text,
        Long since,
        Long until,
        String cursor,
        int pageSize,
        String eventId,
        String correlationId,
        String parentId) {

    public static final int DEFAULT_PAGE_SIZE = 200;

    public ActivityQuery {
        // One extra row is needed by buffered merge-for-reads to determine whether another page exists.
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, 2001);
    }

    /** Existing public page query; exact selectors are used only by selected-evidence reads. */
    public ActivityQuery(
            String instanceId,
            String type,
            String severity,
            String text,
            Long since,
            Long until,
            String cursor,
            int pageSize) {
        this(instanceId, type, severity, text, since, until, cursor, pageSize, null, null, null);
    }

    /** A query for the newest page for {@code instanceId} with no filters, at the default page size. */
    public static ActivityQuery firstPage(String instanceId) {
        return new ActivityQuery(instanceId, null, null, null, null, null, null, DEFAULT_PAGE_SIZE);
    }

    public ActivityQuery withCursor(String newCursor) {
        return new ActivityQuery(
                instanceId, type, severity, text, since, until, newCursor, pageSize, eventId, correlationId, parentId);
    }

    public ActivityQuery withPageSize(int newPageSize) {
        return new ActivityQuery(
                instanceId, type, severity, text, since, until, cursor, newPageSize, eventId, correlationId, parentId);
    }

    public ActivityQuery withEventId(String id) {
        return new ActivityQuery(
                instanceId, type, severity, text, since, until, cursor, pageSize, id, correlationId, parentId);
    }

    public ActivityQuery withCorrelationId(String id) {
        return new ActivityQuery(
                instanceId, type, severity, text, since, until, cursor, pageSize, eventId, id, parentId);
    }

    public ActivityQuery withParentId(String id) {
        return new ActivityQuery(
                instanceId, type, severity, text, since, until, cursor, pageSize, eventId, correlationId, id);
    }

    String normalizedType() {
        return BlankStrings.blankToNull(type);
    }

    String normalizedSeverity() {
        return BlankStrings.blankToNull(severity);
    }

    String normalizedText() {
        return BlankStrings.blankToNull(text);
    }
}
