package io.github.jdubois.bootui.engine.crac.fixtures;

/** Captures a monotonic baseline, which CRAC-TIME-001 deliberately does not call wall-clock time. */
public class MonotonicTimeHolder {

    static final long START_TICK = System.nanoTime();

    public long startTick() {
        return START_TICK;
    }
}
