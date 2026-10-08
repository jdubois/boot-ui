package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hands one call's progress from the tool thread to its stream writer, through the request's {@link
 * McpProgressThrottle}, under one lock: an event the throttle released is always taken before the newer event it may
 * hold, so the writer sends strictly increasing progress. {@link #offer} never blocks: the writer holds this lock only
 * while it picks the next event, never across a write to the client, which is what lets it serve as the progress
 * listener that {@link io.github.jdubois.bootui.engine.progress.OperationProgress} calls under its own lock.
 */
final class McpProgressOutbox {

    private final McpProgressThrottle throttle;
    private final ArrayDeque<ProgressEvent> ready = new ArrayDeque<>();
    private boolean signalled;

    McpProgressOutbox(McpProgressThrottle throttle) {
        this.throttle = throttle;
    }

    /** Tool thread: queues {@code event} now, or holds it until the rate limit allows it. Never blocks. */
    synchronized void offer(ProgressEvent event) {
        ProgressEvent now = throttle.offer(event);
        if (now != null) {
            ready.add(now);
            notifyAll();
        }
    }

    /** Wakes the writer so it re-reads the call's state. */
    synchronized void signal() {
        signalled = true;
        notifyAll();
    }

    /**
     * Writer: the next event that may be sent, waiting up to {@code maxNanos}; {@code null} on timeout or {@link
     * #signal()}.
     */
    synchronized ProgressEvent take(long maxNanos) throws InterruptedException {
        long deadline = System.nanoTime() + maxNanos;
        while (true) {
            if (!ready.isEmpty()) {
                return ready.poll();
            }
            ProgressEvent held = throttle.poll();
            if (held != null) {
                return held;
            }
            if (signalled) {
                signalled = false;
                return null;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            long untilToken = throttle.nanosUntilNextToken();
            TimeUnit.NANOSECONDS.timedWait(this, untilToken > 0 ? Math.min(remaining, untilToken) : remaining);
        }
    }

    /** Writer: every queued and held event, in order, so they are sent before the final response. */
    synchronized List<ProgressEvent> drainAll() {
        List<ProgressEvent> events = new ArrayList<>(ready);
        ready.clear();
        ProgressEvent held = throttle.drainPending();
        if (held != null) {
            events.add(held);
        }
        return events;
    }
}
