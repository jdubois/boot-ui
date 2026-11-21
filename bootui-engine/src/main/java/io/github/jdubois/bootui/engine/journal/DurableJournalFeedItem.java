package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.Objects;

/**
 * One newest-first durable Live Activity feed item, loaded without decoding its typed journal payload.
 *
 * @param origin namespace, application, run, and instance origin
 * @param globalSequence store-wide durable ordering token
 * @param journalSequence the entry's sequence in its run
 * @param timestamp occurrence time in epoch milliseconds
 * @param source original journal source name
 * @param activity masked Live Activity projection, or {@code null} when no projection is available
 */
public record DurableJournalFeedItem(
        DurableJournalOrigin origin,
        long globalSequence,
        long journalSequence,
        long timestamp,
        String source,
        ActivityEntryDto activity) {

    public DurableJournalFeedItem {
        origin = Objects.requireNonNull(origin, "origin must not be null");
        if (globalSequence < 0) {
            throw new IllegalArgumentException("globalSequence must not be negative");
        }
        if (journalSequence < 0) {
            throw new IllegalArgumentException("journalSequence must not be negative");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    public boolean projectionAvailable() {
        return activity != null;
    }
}
