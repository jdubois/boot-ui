package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Rate-limits the progress notifications of one MCP request, as MCP asks of both parties ("SHOULD implement rate
 * limiting to prevent flooding").
 *
 * <p>A token bucket allows a burst of {@link #BURST} notifications and then one per {@link #INTERVAL_MILLIS}
 * milliseconds. An event offered without a token is held as the pending event, replacing an older pending one: only
 * events over the rate limit are coalesced, and since progress strictly increases the newest is the one worth sending.
 * The transport sends the pending event when a token frees up, and {@link #drainPending()} flushes it before the final
 * response so the last reported state is never lost. A 30-second call therefore emits at most
 * {@code 8 + 30000 / 250 = 128} notifications.
 */
public final class McpProgressThrottle {

    /** Notifications sent without waiting. */
    public static final int BURST = 8;
    /** Minimum spacing of notifications once the burst is spent. */
    public static final long INTERVAL_MILLIS = 250;

    private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS);

    private final LongSupplier nanoClock;
    private int tokens = BURST;
    private long lastRefill;
    private ProgressEvent pending;

    public McpProgressThrottle() {
        this(System::nanoTime);
    }

    McpProgressThrottle(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.lastRefill = nanoClock.getAsLong();
    }

    /** Returns {@code event} when it may be sent now; otherwise holds it as the pending event and returns {@code null}. */
    public synchronized ProgressEvent offer(ProgressEvent event) {
        Objects.requireNonNull(event, "event");
        refill();
        if (pending == null && tokens > 0) {
            tokens--;
            return event;
        }
        pending = event;
        return null;
    }

    /** The pending event when a token is available now, otherwise {@code null}. */
    public synchronized ProgressEvent poll() {
        refill();
        if (pending == null || tokens == 0) {
            return null;
        }
        tokens--;
        ProgressEvent event = pending;
        pending = null;
        return event;
    }

    /** The pending event regardless of tokens, so it is sent before the final response; {@code null} when none. */
    public synchronized ProgressEvent drainPending() {
        ProgressEvent event = pending;
        pending = null;
        return event;
    }

    /** Nanoseconds until {@link #poll()} can return the pending event; zero when it can now or nothing is pending. */
    public synchronized long nanosUntilNextToken() {
        refill();
        if (pending == null || tokens > 0) {
            return 0;
        }
        return Math.max(0, INTERVAL_NANOS - (nanoClock.getAsLong() - lastRefill));
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed < INTERVAL_NANOS) {
            return;
        }
        long earned = elapsed / INTERVAL_NANOS;
        tokens = (int) Math.min(BURST, tokens + earned);
        lastRefill = tokens == BURST ? now : lastRefill + earned * INTERVAL_NANOS;
    }
}
