package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * A forward, keyset-paged query of the events belonging to one run.
 *
 * @param scope the run to read
 * @param source optional event-source filter
 * @param failedOrSlowOnly whether to return only failed-or-slow events
 * @param sinceEpochMillis inclusive lower wall-clock bound, or {@code null}
 * @param untilEpochMillis inclusive upper wall-clock bound, or {@code null}
 * @param requestId optional exact projected request id
 * @param executionId optional exact projected execution id
 * @param traceId optional exact projected trace id
 * @param cursor exclusive sequence boundary from a previous page
 * @param pageSize maximum entries to return
 */
public record DurableJournalQuery(
        DurableJournalScope scope,
        JournalSource source,
        boolean failedOrSlowOnly,
        Long sinceEpochMillis,
        Long untilEpochMillis,
        String requestId,
        String executionId,
        String traceId,
        DurableJournalCursor cursor,
        int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 200;
    public static final int MAX_PAGE_SIZE = 5_000;

    public DurableJournalQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        requestId = projected("requestId", requestId);
        executionId = projected("executionId", executionId);
        traceId = projected("traceId", traceId);
        if (sinceEpochMillis != null && untilEpochMillis != null && sinceEpochMillis > untilEpochMillis) {
            throw new IllegalArgumentException("sinceEpochMillis must not be after untilEpochMillis");
        }
    }

    public DurableJournalQuery(DurableJournalScope scope, DurableJournalCursor cursor, int pageSize) {
        this(scope, null, false, null, null, null, null, null, cursor, pageSize);
    }

    public DurableJournalQuery(DurableJournalScope scope, String cursor, int pageSize) {
        this(scope, DurableJournalCursor.decode(cursor), pageSize);
    }

    /** The first page of all events in {@code scope}. */
    public static DurableJournalQuery firstPage(DurableJournalScope scope) {
        return new DurableJournalQuery(scope, null, false, null, null, null, null, null, null, DEFAULT_PAGE_SIZE);
    }

    public DurableJournalQuery withCursor(DurableJournalCursor newCursor) {
        return new DurableJournalQuery(
                scope,
                source,
                failedOrSlowOnly,
                sinceEpochMillis,
                untilEpochMillis,
                requestId,
                executionId,
                traceId,
                newCursor,
                pageSize);
    }

    private static String projected(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > DurableJournalStore.MAX_PROJECTION_LENGTH) {
            throw new IllegalArgumentException(
                    field + " exceeds " + DurableJournalStore.MAX_PROJECTION_LENGTH + " characters");
        }
        return value;
    }
}
