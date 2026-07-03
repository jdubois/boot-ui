package io.github.jdubois.bootui.engine.activity;

import java.util.List;

/**
 * Storage abstraction for the Live Activity persistence option: a place to durably (or
 * transiently) keep captured {@link io.github.jdubois.bootui.core.dto.ActivityEntryDto} entries and
 * query them back with pagination/filter/search.
 *
 * <p>This is the seam the design's "swappable storage" requirement hangs off: {@link
 * InMemoryActivityStore} is the default (and today's formalized behavior), {@link JdbcActivityStore} is
 * the direct-JDBC SQL-database implementation, and {@link BufferedActivityStore} composes any durable
 * implementation with a hot in-memory cache to add write-behind buffering, scheduled flush,
 * merge-for-reads and failure re-queueing. Nothing outside this package needs to know which
 * implementation is in play.</p>
 *
 * <p>Implementations must be safe to call concurrently: {@link #append}/{@link #appendBatch} may be
 * called from the capture coordinator's thread while {@link #query} is called from an HTTP request
 * thread.</p>
 */
public interface ActivityStore extends AutoCloseable {

    /** Appends one entry. Equivalent to {@code appendBatch(List.of(entry))}. */
    default void append(StoredActivityEntry entry) {
        appendBatch(List.of(entry));
    }

    /** Appends a batch of entries, preserving their relative order where the implementation orders by insertion. */
    void appendBatch(List<StoredActivityEntry> entries);

    /** Runs a query, returning a page of matching entries newest-first. */
    ActivityPage query(ActivityQuery query);

    /**
     * Deletes {@code instanceId}'s own rows older than {@code olderThanEpochMillis}, enforcing the
     * configured retention window. The default implementation does nothing: a bounded in-memory store
     * already self-evicts by capacity and has no separate retention concept.
     */
    default void prune(String instanceId, long olderThanEpochMillis) {}

    /**
     * Releases any resources (scheduled tasks, connections) the store holds. The default
     * implementation does nothing, since a plain in-memory or JDBC store holds nothing that needs
     * releasing beyond garbage collection; {@link BufferedActivityStore} overrides this to stop its
     * flush scheduler.
     */
    @Override
    default void close() {}
}
