package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityCaptureCoordinator;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivitySequencer;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Writes Live Activity's durable history from the runtime journal ({@code docs/PLAN-v2.md} §5.3): a journal listener
 * that renders each recorded batch as the journal's feed renders it and hands the rows to the capture coordinator, so
 * a burst is captured as completely as the journal records it, with no polling window to fall behind.
 *
 * <p>Rows carry only what the journal records: no bind values, principals, or exception and log messages, which is
 * never less masked than {@code MASKED} (§8). Each row names its parent request or execution by id, since a parent is
 * recorded after its children. Rows of a disabled panel are not written. It runs on the journal's dispatcher, where
 * the coordinator's append only buffers rows for the store's own flusher.</p>
 */
public final class JournalActivityCapture implements JournalListener, ActivityCapture {

    /** The most open requests whose {@code SELECT} counts are kept until they complete. */
    static final int MAX_PENDING_REQUESTS = 4_096;

    private final RuntimeJournal journal;
    private final JournalActivityFeed feed;
    private final ActivityCaptureCoordinator coordinator;
    private final Predicate<String> panelEnabled;
    private final Map<String, Map<String, Integer>> pendingSelects = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Integer>> eldest) {
            return size() > MAX_PENDING_REQUESTS;
        }
    };

    /** The most recently recorded requests' trace ids, so an AI call exported after its request nests under it. */
    private final Map<String, String> requestsByTrace = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_PENDING_REQUESTS;
        }
    };

    private JournalActivityCapture(
            RuntimeJournal journal,
            JournalActivityFeed feed,
            ActivityCaptureCoordinator coordinator,
            Predicate<String> panelEnabled) {
        this.journal = journal;
        this.feed = feed;
        this.coordinator = coordinator;
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
    }

    /**
     * Starts capturing {@code journal}'s recorded batches into {@code store} as {@code feed} renders them, for adapters
     * whose Live Activity feed comes from the runtime journal. The caller owns closing the returned capture.
     *
     * @param reserved which rows the store remembers longer, as for the poller
     * @param panelEnabled whether a panel, by its id, is enabled; a disabled panel's rows are not written
     */
    public static JournalActivityCapture start(
            ActivityStore store,
            ActivityPersistenceSettings settings,
            Predicate<ActivityEntryDto> reserved,
            RuntimeJournal journal,
            JournalActivityFeed feed,
            Predicate<String> panelEnabled) {
        ActivityCaptureCoordinator coordinator = new ActivityCaptureCoordinator(
                store, new ActivitySequencer(settings.instanceId()), settings.bufferMaxEntries(), reserved);
        return start(journal, feed, coordinator, panelEnabled);
    }

    /** Starts capturing every batch {@code journal} records from now on, until closed. */
    public static JournalActivityCapture start(
            RuntimeJournal journal,
            JournalActivityFeed feed,
            ActivityCaptureCoordinator coordinator,
            Predicate<String> panelEnabled) {
        JournalActivityCapture capture = new JournalActivityCapture(journal, feed, coordinator, panelEnabled);
        journal.addListener(capture);
        return capture;
    }

    @Override
    public synchronized void onEntries(List<JournalEntry> entries) {
        List<JournalEntry> visible = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            if (visible(entry.event())) {
                visible.add(entry);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        List<ActivityEntryDto> rows = feed.renderForCapture(visible, journal::eventId, pendingSelects, requestsByTrace);
        coordinator.ingest(rows);
    }

    /** Stops capturing; batches the journal records afterwards are not written. */
    @Override
    public void close() {
        journal.removeListener(this);
    }

    private boolean visible(RuntimeEvent event) {
        String panel = JournalActivityReports.panelOf(event);
        try {
            return panel == null || panelEnabled.test(panel);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
