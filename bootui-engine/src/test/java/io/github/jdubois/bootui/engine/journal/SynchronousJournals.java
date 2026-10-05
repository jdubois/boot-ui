package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.util.function.IntPredicate;

/**
 * Journals without a dispatcher thread, for tests in other packages that must decide exactly which offer the queue drops
 * and when the offered events are processed.
 */
public final class SynchronousJournals {

    private SynchronousJournals() {}

    /**
     * A journal whose events are processed only by {@link #dispatch}, and whose queue drops the offers {@code drops}
     * accepts, counting each as a full queue does.
     *
     * @param drops which offers to drop, by their zero-based position among this journal's queue offers
     */
    public static RuntimeJournal create(RuntimeJournalSettings settings, IntPredicate drops) {
        int[] offers = {0};
        return new RuntimeJournal(settings, RunIdentity.start(), false, () -> {
            if (drops.test(offers[0]++)) {
                throw new IllegalStateException("Dropped by the test");
            }
        });
    }

    /** Processes every queued event on the calling thread. */
    public static void dispatch(RuntimeJournal journal) {
        journal.dispatchPending();
    }
}
