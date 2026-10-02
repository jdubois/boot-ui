package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityCaptureCoordinator;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivitySequencer;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
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
 *
 * <p>An AI call carries only its trace id, and its span is often exported before its request completes, so a call no
 * recorded request can claim yet is held back until one does ({@link AiCallOwners}). It is written on its own once
 * the journal has recorded {@value #AI_CALL_WAIT_MILLIS} ms past its end without such a request, once more than
 * {@value #MAX_PENDING_AI_CALLS} calls wait, or when the capture or the journal closes, so the history attributes a call
 * as the live feed does, whichever the journal recorded first. A request recorded after a call was written, which
 * overlaps the one it was written under and shares its trace, does not move it, although the live feed then nests the
 * call under neither.</p>
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

    /** The most AI calls held back until a request claims them. */
    static final int MAX_PENDING_AI_CALLS = 1_024;

    /** How long past its end, in the journal's recorded time, an AI call waits for a request to claim it. */
    static final long AI_CALL_WAIT_MILLIS = 120_000;

    /** Every recorded request's trace id and time span, so each AI call is attributed as the live feed attributes it. */
    private final AiCallOwners aiCallOwners = AiCallOwners.bounded();

    /** AI calls no recorded request claims yet, in recorded order. */
    private final ArrayDeque<JournalEntry> pendingAiCalls = new ArrayDeque<>();

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
        long recordedUntil = Long.MIN_VALUE;
        for (JournalEntry entry : entries) {
            aiCallOwners.learn(entry.event());
            recordedUntil = Math.max(recordedUntil, endMillis(entry.event()));
        }
        List<JournalEntry> ready = new ArrayList<>(entries.size());
        releasePendingAiCalls(ready, recordedUntil, false);
        for (JournalEntry entry : entries) {
            if (!visible(entry.event())) {
                continue;
            }
            if (aiCallOwners.unresolved(entry.event())) {
                pendingAiCalls.addLast(entry);
            } else {
                ready.add(entry);
            }
        }
        while (pendingAiCalls.size() > MAX_PENDING_AI_CALLS) {
            ready.add(pendingAiCalls.removeFirst());
        }
        write(ready);
    }

    /** Writes the AI calls still held back, each on its own, after the journal's last batch of the run. */
    @Override
    public synchronized void onClose() {
        writePendingAiCalls();
    }

    /** Forgets what the cleared recording held: its requests, open {@code SELECT} counts, and held-back AI calls. */
    @Override
    public synchronized void onClear() {
        aiCallOwners.clear();
        pendingSelects.clear();
        pendingAiCalls.clear();
    }

    /**
     * Stops capturing and writes the AI calls still held back, each on its own; batches the journal records afterwards
     * are not written.
     */
    @Override
    public void close() {
        journal.removeListener(this);
        synchronized (this) {
            writePendingAiCalls();
        }
    }

    private void writePendingAiCalls() {
        List<JournalEntry> ready = new ArrayList<>(pendingAiCalls.size());
        releasePendingAiCalls(ready, Long.MAX_VALUE, true);
        write(ready);
    }

    /** Held-back AI calls, for tests. */
    synchronized int pendingAiCalls() {
        return pendingAiCalls.size();
    }

    /**
     * Moves to {@code ready} each held-back AI call a request now claims, or whose attribution is now ambiguous, or that
     * no request claimed within {@link #AI_CALL_WAIT_MILLIS} of its end by {@code recordedUntil}; {@code all} moves
     * every one.
     */
    private void releasePendingAiCalls(List<JournalEntry> ready, long recordedUntil, boolean all) {
        Iterator<JournalEntry> pending = pendingAiCalls.iterator();
        while (pending.hasNext()) {
            JournalEntry call = pending.next();
            if (all
                    || !aiCallOwners.unresolved(call.event())
                    || recordedUntil - endMillis(call.event()) > AI_CALL_WAIT_MILLIS) {
                ready.add(call);
                pending.remove();
            }
        }
    }

    private void write(List<JournalEntry> ready) {
        if (ready.isEmpty()) {
            return;
        }
        coordinator.ingest(feed.renderForCapture(ready, journal::eventId, pendingSelects, aiCallOwners));
    }

    private static long endMillis(RuntimeEvent event) {
        long nanos = event.durationNanos();
        return nanos <= 0 ? event.epochMillis() : event.epochMillis() + nanos / 1_000_000;
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
