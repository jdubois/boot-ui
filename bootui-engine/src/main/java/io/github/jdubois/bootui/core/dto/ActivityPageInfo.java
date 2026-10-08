package io.github.jdubois.bootui.core.dto;

/**
 * Pagination metadata for a Live Activity page. Only present when the dashboard is browsing entries
 * served by the durable/buffered activity store (persistence enabled); the default in-memory re-merge
 * path never sets this, so existing consumers of {@link LiveActivityReport} are unaffected.
 *
 * @param persistent whether this response was served by the durable activity store, as opposed to the
 *     default live in-memory re-merge
 * @param nextCursor opaque cursor to pass back as the {@code cursor} query parameter to fetch the next
 *     (older) page, or {@code null} when there is nothing older to fetch
 * @param hasMore whether at least one more entry exists beyond this page
 */
public record ActivityPageInfo(boolean persistent, String nextCursor, boolean hasMore) {}
