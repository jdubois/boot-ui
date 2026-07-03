package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Assigns the {@code (instanceId, seq)} identity a captured entry keeps for its whole lifetime.
 *
 * <p>Exactly one {@link ActivitySequencer} exists per running BootUI instance, owned by whichever
 * component first captures entries (the capture coordinator, or a test harness appending directly). It
 * is the single point where a monotonic sequence is handed out, so every downstream {@link ActivityStore}
 * — including both tiers of a {@link BufferedActivityStore} — agrees on the same identity for a given
 * logical entry.</p>
 */
public final class ActivitySequencer {

    private final String instanceId;
    private final AtomicLong sequence = new AtomicLong();

    public ActivitySequencer(String instanceId) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
    }

    public String instanceId() {
        return instanceId;
    }

    /** Stamps the next sequence number in this instance onto {@code entry}. */
    public StoredActivityEntry stamp(ActivityEntryDto entry) {
        return new StoredActivityEntry(instanceId, sequence.incrementAndGet(), entry);
    }
}
