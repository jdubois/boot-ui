package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Feeds an {@link ActivityStore} from Live Activity's existing merged view, without adding any new
 * low-level instrumentation to the four signal sources it already reads (HTTP exchanges, SQL trace,
 * exceptions, security events).
 *
 * <p>Each adapter already computes the merged, already-masked, reverse-chronological feed on demand
 * (Spring's {@code LiveActivityService.report(...)}, Quarkus's {@code LiveActivityAssembler.report(...)}).
 * This coordinator is polled periodically (see the adapter's own scheduling) with that same list and
 * captures whatever it has not seen yet, oldest-first, so entries land in the store in true
 * chronological order.</p>
 *
 * <p>"Not seen yet" is tracked by a bounded set of already-captured entry ids rather than a
 * timestamp-based watermark: entry timestamps from four different sources can tie, and a pure
 * timestamp cursor cannot reliably tell two same-millisecond entries apart. An id still present in the
 * current view is never evicted from that set, so a record the source buffers keep for a long time — such as
 * a failure held in a failure-preserving buffer's reserved share — is captured exactly once while it stays
 * visible.</p>
 *
 * <p>An entry can also leave the view and come back while its source still holds it: newer entries of other
 * sources hide it from Spring MVC's capped feed until they are cleared (for example when {@code bootui.free-on-idle}
 * releases captured SQL), a source drops out of the feed and returns, or an exception group recurs under the same
 * id. Failed and slow entries are therefore also remembered in a second window per entry type, of the same size,
 * which routine entries and entries of other types never evict. An entry is remembered there when the
 * adapter-supplied {@code reserved} rule says a failure-preserving buffer flags its record for the reserved share
 * ({@code ReservedActivityEntries} in every adapter, which applies each buffer's own classification and thresholds:
 * {@code 5xx} and slow requests, including a slow {@code 4xx} whose severity is {@code WARN}; failed and slow
 * statements; and failed, {@code 4xx}/{@code 5xx}, and slow REST calls), or when its severity is {@code ERROR} or
 * {@code SLOW}, which covers the failures of sources without a reserved share. Such an entry is recognized when it
 * reappears unless more remembered entries of its own type than that window holds arrived meanwhile, so neither a
 * failing scheduled job nor a flood of slow requests can make persistence forget a failed statement.</p>
 *
 * <p>Every set stays bounded by the configured window plus the size of the view, with one remembered window per
 * entry type of Live Activity's fixed vocabulary. The trade-off is deliberately simple and documented: if more
 * distinct new entries appear between two polls than the configured window can hold, the oldest ones may be evicted
 * from the "seen" set and — if also no longer present in the next poll's bounded merged view — never captured, and a
 * routine entry that reappears after more entries than the window holds were captured is captured again. Lowering the
 * poll interval or raising {@code bootui.activity.max-entries} widens the window and mitigates this.</p>
 */
public final class ActivityCaptureCoordinator {

    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_SLOW = "SLOW";

    private final ActivityStore store;
    private final ActivitySequencer sequencer;
    private final int seenCapacity;
    private final Predicate<ActivityEntryDto> reserved;
    private final Set<String> seenIds = new LinkedHashSet<>();
    private final Map<String, Set<String>> rememberedIdsByType = new HashMap<>();
    private final Object lock = new Object();

    /**
     * @param store where captured entries are appended
     * @param sequencer stamps each captured entry with its instance id and sequence
     * @param seenCapacity size of each "seen" window, clamped to at least {@code 16}
     * @param reserved whether an entry stands for a record a failure-preserving capture buffer flags for its reserved
     *     share, and so belongs in its type's remembered window
     */
    public ActivityCaptureCoordinator(
            ActivityStore store, ActivitySequencer sequencer, int seenCapacity, Predicate<ActivityEntryDto> reserved) {
        this.store = store;
        this.sequencer = sequencer;
        this.seenCapacity = Math.max(16, seenCapacity);
        this.reserved = Objects.requireNonNull(reserved, "reserved");
    }

    /**
     * Captures any entry in {@code latestNewestFirst} not already captured, appending in chronological
     * (oldest-first) order.
     *
     * @param latestNewestFirst the current merged feed, newest-first (typically already capped to
     *     {@code bootui.activity.max-entries})
     */
    public void ingest(List<ActivityEntryDto> latestNewestFirst) {
        if (latestNewestFirst == null || latestNewestFirst.isEmpty()) {
            return;
        }
        List<StoredActivityEntry> toCapture = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (ActivityEntryDto entry : latestNewestFirst) {
            if (entry.id() != null) {
                present.add(entry.id());
            }
        }
        synchronized (lock) {
            for (int i = latestNewestFirst.size() - 1; i >= 0; i--) {
                ActivityEntryDto entry = latestNewestFirst.get(i);
                String id = entry.id();
                if (id == null || seenIds.contains(id) || isRemembered(id)) {
                    continue;
                }
                toCapture.add(sequencer.stamp(entry));
                seenIds.add(id);
                if (isRememberedLonger(entry)) {
                    rememberedIdsByType
                            .computeIfAbsent(
                                    Objects.requireNonNullElse(entry.type(), ""), type -> new LinkedHashSet<>())
                            .add(id);
                }
            }
            trim(seenIds, present);
            for (Set<String> remembered : rememberedIdsByType.values()) {
                trim(remembered, present);
            }
        }
        if (!toCapture.isEmpty()) {
            store.appendBatch(toCapture);
        }
    }

    private boolean isRemembered(String id) {
        for (Set<String> remembered : rememberedIdsByType.values()) {
            if (remembered.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** A record a buffer reserves, or a failed or slow entry of any source. */
    private boolean isRememberedLonger(ActivityEntryDto entry) {
        return reserved.test(entry)
                || SEVERITY_ERROR.equals(entry.severity())
                || SEVERITY_SLOW.equals(entry.severity());
    }

    /** Ids currently remembered in the first window, for tests of its bound. */
    int seenCount() {
        synchronized (lock) {
            return seenIds.size();
        }
    }

    /** Ids currently remembered in {@code type}'s second window, for tests of its bound. */
    int rememberedCount(String type) {
        synchronized (lock) {
            Set<String> remembered = rememberedIdsByType.get(type);
            return remembered == null ? 0 : remembered.size();
        }
    }

    /** Evicts the oldest ids beyond the capacity, keeping every id still present in the current view. */
    private void trim(Set<String> seen, Set<String> present) {
        Iterator<String> oldest = seen.iterator();
        while (seen.size() > seenCapacity && oldest.hasNext()) {
            if (!present.contains(oldest.next())) {
                oldest.remove();
            }
        }
    }
}
