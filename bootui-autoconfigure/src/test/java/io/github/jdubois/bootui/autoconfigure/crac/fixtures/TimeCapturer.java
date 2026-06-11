package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

import java.time.Instant;

/** Captures wall-clock time in a static initializer (CRAC-TIME-001). */
public class TimeCapturer {

    static final long STARTED_AT = System.currentTimeMillis();
    static final Instant STARTED_INSTANT = Instant.now();

    public long startedAt() {
        return STARTED_AT;
    }

    public Instant startedInstant() {
        return STARTED_INSTANT;
    }
}
