package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Bounded in-memory ring buffer implementation of {@link ActivityStore}. This formalizes today's
 * behavior (Live Activity keeps only what fits in memory, nothing survives a restart) as one concrete,
 * first-class implementation of the storage abstraction — the default when persistence is disabled, and
 * reused as the hot cache inside {@link BufferedActivityStore} when it is enabled.
 *
 * <p>Thread-safe: {@link #appendBatch} and {@link #query} may be called concurrently from the capture
 * thread and HTTP request threads respectively.</p>
 */
public final class InMemoryActivityStore implements ActivityStore {

    private final int capacity;
    private final Deque<StoredActivityEntry> buffer = new ArrayDeque<>();
    private final Object lock = new Object();

    public InMemoryActivityStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void appendBatch(List<StoredActivityEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (StoredActivityEntry entry : entries) {
                buffer.addLast(entry);
            }
            while (buffer.size() > capacity) {
                buffer.removeFirst();
            }
        }
    }

    @Override
    public ActivityPage query(ActivityQuery query) {
        List<StoredActivityEntry> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        ActivityCursor cursor = ActivityCursor.decode(query.cursor());
        String type = query.normalizedType();
        String severity = query.normalizedSeverity();
        String text = query.normalizedText();

        List<StoredActivityEntry> matches = new ArrayList<>();
        // The buffer is stored oldest-first; walk it newest-first to return pages in the expected order.
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            StoredActivityEntry stored = snapshot.get(i);
            if (!stored.instanceId().equals(query.instanceId())) {
                continue;
            }
            if (!matchesFilters(stored.entry(), type, severity, text, query.since(), query.until())) {
                continue;
            }
            if (cursor != null && !cursor.isAfter(stored.entry().timestamp(), stored.seq())) {
                continue;
            }
            matches.add(stored);
        }

        int pageSize = query.pageSize();
        boolean hasMore = matches.size() > pageSize;
        List<StoredActivityEntry> page = hasMore ? matches.subList(0, pageSize) : matches;
        String nextCursor = null;
        if (hasMore) {
            StoredActivityEntry last = page.get(page.size() - 1);
            nextCursor = new ActivityCursor(last.entry().timestamp(), last.seq()).encode();
        }
        return new ActivityPage(page, nextCursor, hasMore);
    }

    private static boolean matchesFilters(
            ActivityEntryDto entry, String type, String severity, String text, Long since, Long until) {
        if (since != null && entry.timestamp() <= since) {
            return false;
        }
        if (until != null && entry.timestamp() > until) {
            return false;
        }
        if (type != null && !type.equalsIgnoreCase(entry.type())) {
            return false;
        }
        if (severity != null && (entry.severity() == null || !severity.equalsIgnoreCase(entry.severity()))) {
            return false;
        }
        if (text != null && !matchesText(entry, text)) {
            return false;
        }
        return true;
    }

    private static boolean matchesText(ActivityEntryDto entry, String needle) {
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(entry.summary(), lowerNeedle)
                || containsIgnoreCase(entry.detail(), lowerNeedle)
                || containsIgnoreCase(entry.path(), lowerNeedle)
                || containsIgnoreCase(entry.method(), lowerNeedle);
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
