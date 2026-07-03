package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * timestamp cursor cannot reliably tell two same-millisecond entries apart. The trade-off is
 * deliberately simple and documented: if more distinct new entries appear between two polls than the
 * configured window can hold, the oldest ones may be evicted from the "seen" set and — if also no
 * longer present in the next poll's bounded merged view — never captured. Lowering the poll interval or
 * raising {@code bootui.activity.max-entries} widens the window and mitigates this.</p>
 */
public final class ActivityCaptureCoordinator {

    private final ActivityStore store;
    private final ActivitySequencer sequencer;
    private final int seenCapacity;
    private final Set<String> seenIds = new LinkedHashSet<>();
    private final Object lock = new Object();

    public ActivityCaptureCoordinator(ActivityStore store, ActivitySequencer sequencer, int seenCapacity) {
        this.store = store;
        this.sequencer = sequencer;
        this.seenCapacity = Math.max(16, seenCapacity);
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
        synchronized (lock) {
            for (int i = latestNewestFirst.size() - 1; i >= 0; i--) {
                ActivityEntryDto entry = latestNewestFirst.get(i);
                String id = entry.id();
                if (id == null || seenIds.contains(id)) {
                    continue;
                }
                toCapture.add(sequencer.stamp(entry));
                markSeen(id);
            }
        }
        if (!toCapture.isEmpty()) {
            store.appendBatch(toCapture);
        }
    }

    private void markSeen(String id) {
        seenIds.add(id);
        while (seenIds.size() > seenCapacity) {
            Iterator<String> oldest = seenIds.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
