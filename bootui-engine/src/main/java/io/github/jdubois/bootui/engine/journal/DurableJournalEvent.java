package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.Objects;

/**
 * One durable journal event and the Live Activity projection derived from the same retained evidence.
 *
 * <p>{@link #activity()} is {@code null} when the projection was unavailable (for example, for an event that has no
 * Live Activity row or for a writer using the JournalEntry-only append overload). A missing projection is data, not a
 * reason to synthesize an activity entry.
 */
public record DurableJournalEvent(JournalEntry entry, ActivityEntryDto activity) {

    public DurableJournalEvent {
        entry = Objects.requireNonNull(entry, "entry must not be null");
    }

    public boolean projectionAvailable() {
        return activity != null;
    }
}
