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
}
