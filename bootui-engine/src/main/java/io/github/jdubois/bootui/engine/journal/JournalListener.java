package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * Receives each batch of accepted events on the journal's dispatcher thread, after they are retained, such as the
 * incremental aggregates and the Live Activity persistence subscriber ({@code docs/PLAN-v2.md} §5.2, §5.3). It sees
 * every accepted event, including those later evicted, so it must be fast and must not block; a listener that throws
 * is counted and skipped for that batch. Clearing the recording or offloading memory waits for the batch in progress,
 * so a listener must not clear, offload, or close the journal from a callback, nor while holding a lock its callbacks take.
 */
@FunctionalInterface
public interface JournalListener {

    /** Called with the accepted events of one batch, in sequence order. */
    void onEntries(List<JournalEntry> entries);

    /**
     * Called once when the journal closes at the end of its run, after its last batch, on the closing thread. The run
     * summary is recorded here ({@code docs/PLAN-v2.md} §5.2).
     */
    default void onClose() {}

    /**
     * Called on the clearing thread only by the confirmation-gated <b>Clear recording</b> action. Listeners that keep
     * only hot state can implement {@link #onMemoryOffload()} and use that same behavior for both actions.
     */
    default void onClear() {
        onMemoryOffload();
    }

    /**
     * Called when <b>Free BootUI memory</b> or low-footprint mode drops the hot recording. Listeners may discard
     * in-memory projections here, but must leave persisted history and its pending writes alone.
     */
    default void onMemoryOffload() {}
}
