package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.Objects;

/**
 * A Live Activity entry stamped with the bookkeeping every {@link ActivityStore} needs: which BootUI
 * instance produced it (multi-tenant partition key) and its monotonic sequence within that instance.
 *
 * <p>Stamping happens exactly once, at the point an entry first enters the system (see
 * {@code ActivitySequencer}), so every {@link ActivityStore} implementation — and every tier of a
 * {@link BufferedActivityStore} — agrees on the same {@code (instanceId, seq)} identity for a given
 * entry. That shared identity is what lets a merge-for-reads dedupe an entry that is visible in both the
 * hot in-memory cache and the durable store.</p>
 *
 * @param instanceId identifier of the BootUI instance that captured this entry (multi-tenant partition key)
 * @param seq monotonic sequence number of this entry within {@code instanceId}, assigned once at capture time
 * @param entry the normalized, already-masked activity entry
 */
public record StoredActivityEntry(String instanceId, long seq, ActivityEntryDto entry) {

    public StoredActivityEntry {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
    }
}
