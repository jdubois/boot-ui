package io.github.jdubois.bootui.sample.caught;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The agent overhead benchmark's caught-exceptions work (M5-6a2): one exception caught per request. Half are caught
 * where they are thrown; the other half unwind through {@value #DEPTH} application frames, each with a handler, so
 * each runs the method's exit handler, then are wrapped, rethrown, and caught. Nothing is logged.
 */
@Component
public class CaughtBenchmark {

    static final int DEPTH = 20;

    private final AtomicLong requests = new AtomicLong();

    public int run() {
        if ((requests.incrementAndGet() & 1L) == 0L) {
            try {
                throw new IOException("benchmark");
            } catch (IOException ex) {
                return 1;
            }
        }
        try {
            return wrapped();
        } catch (IllegalArgumentException ex) {
            return 2;
        }
    }

    private int wrapped() {
        try {
            return depth(DEPTH);
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException("wrapped", ex);
        }
    }

    private int depth(int remaining) {
        if (remaining == 0) {
            throw new IllegalStateException("benchmark");
        }
        try {
            return depth(remaining - 1) + 1;
        } catch (UnsupportedOperationException never) {
            return -1;
        }
    }
}
