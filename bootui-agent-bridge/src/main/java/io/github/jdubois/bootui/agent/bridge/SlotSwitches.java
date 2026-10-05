package io.github.jdubois.bootui.agent.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The runtime sensor switches of claim slots whose claim is no longer current (PLAN-v2 M5-14), so they apply again to the
 * slot's next claim: after a release, or while another application's claim is current. The current claim carries its
 * own slot's switches, and a claim in the same slot takes them from it; whatever replaces a claim of another slot, or
 * releases one, saves that claim's switches here first. Lock-free: one immutable map replaced by compare-and-set, each
 * slot's entry stamped with the generation and switch revision of the claim it came from, so a late save of an older
 * claim never overwrites a newer one. Keeps at most {@value #MAX_SLOTS} slots, forgetting the oldest saved first.
 */
final class SlotSwitches {

    /** The most slots kept. */
    static final int MAX_SLOTS = 8;

    private static final AtomicReference<Map<String, Saved>> SAVED =
            new AtomicReference<Map<String, Saved>>(Collections.<String, Saved>emptyMap());

    private SlotSwitches() {}

    /** The switches saved for {@code slot}, or an empty map. */
    static Map<String, Boolean> saved(String slot) {
        Saved saved = SAVED.get().get(slot);
        return saved == null ? Collections.<String, Boolean>emptyMap() : saved.overrides;
    }

    /** Saves {@code claim}'s switches for its slot, unless a newer claim of that slot saved its own. Never throws. */
    static void save(Claim claim) {
        if (claim == null) {
            return;
        }
        try {
            while (true) {
                Map<String, Saved> current = SAVED.get();
                Saved previous = current.get(claim.slot);
                if (previous != null && !previous.olderThan(claim)) {
                    return;
                }
                if (previous == null && claim.overrides.isEmpty()) {
                    return;
                }
                Map<String, Saved> next = new LinkedHashMap<String, Saved>(current);
                next.remove(claim.slot);
                next.put(claim.slot, new Saved(claim.generation, claim.sensorsRevision, claim.overrides));
                while (next.size() > MAX_SLOTS) {
                    next.remove(next.keySet().iterator().next());
                }
                if (SAVED.compareAndSet(current, Collections.unmodifiableMap(next))) {
                    return;
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Tests only. */
    static void reset() {
        SAVED.set(Collections.<String, Saved>emptyMap());
    }

    /** One slot's saved switches and the claim they came from. */
    static final class Saved {

        final long generation;
        final long revision;
        final Map<String, Boolean> overrides;

        Saved(long generation, long revision, Map<String, Boolean> overrides) {
            this.generation = generation;
            this.revision = revision;
            this.overrides = overrides;
        }

        boolean olderThan(Claim claim) {
            return generation < claim.generation || (generation == claim.generation && revision < claim.sensorsRevision);
        }
    }
}
