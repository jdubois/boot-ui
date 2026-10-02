package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * Receives each batch of accepted events on the journal's dispatcher thread, after they are retained, such as the
 * incremental aggregates and the Live Activity persistence subscriber ({@code docs/PLAN-v2.md} §5.2, §5.3). It sees
 * every accepted event, including those later evicted, so it must be fast and must not block; a listener that throws
 * is counted and skipped for that batch.
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
     * Called when the confirmation-gated <b>Clear recording</b> action drops every retained event, on the clearing
     * thread, so a listener forgets the state it keeps about the cleared events.
     */
    default void onClear() {}
}
