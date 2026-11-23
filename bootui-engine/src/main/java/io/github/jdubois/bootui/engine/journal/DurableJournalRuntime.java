package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/** Process-local publication point for an active durable journal store and its capture. */
public final class DurableJournalRuntime {

    private volatile DurableJournalStore store;
    private volatile DurableJournalCapture capture;

    public synchronized boolean activate(DurableJournalStore store, DurableJournalCapture capture) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(capture, "capture must not be null");
        if (active()) {
            return false;
        }
        this.store = store;
        this.capture = capture;
        return true;
    }

    public DurableJournalStore store() {
        return store;
    }

    public DurableJournalCapture capture() {
        return capture;
    }

    public boolean active() {
        return store != null && capture != null;
    }

    /** Returns merged evidence only when the active writer holds discarded evidence for this journal and scope. */
    public JournalEvidenceSnapshot readCurrentEvidence(RuntimeJournal journal, DurableJournalScope scope) {
        DurableJournalCapture writer = capture;
        if (journal == null || writer == null) {
            return null;
        }
        long generation = journal.stableClearGeneration();
        if (generation < 0) {
            return null;
        }
        DurableJournalStore reader = store;
        DurableJournalCapture.ReadWindow window = writer.readWindow(journal, scope, reader);
        if (window == null) {
            return null;
        }
        JournalEvidenceSnapshot evidence = new DurableJournalEvidence(reader).read(scope, journal, window);
        DurableJournalCapture.ReadWindow after = writer.readWindow(journal, scope, reader);
        // An earlier listener can hold the clear before it reaches the writer's boundary.
        return after != null
                        && after.clearedThrough() == window.clearedThrough()
                        && journal.stableClearGeneration() == generation
                ? evidence
                : null;
    }
}
