package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capped, thread-safe buffer of {@link CapturedHttpExchange} records — the Quarkus capture source for the HTTP
 * Exchanges and Live Activity panels. Spring records into BootUI's own {@code HttpExchangeRepository}, which keeps
 * the same {@link TieredCaptureBuffer} policy, because Actuator's repository is the Spring capture seam.
 *
 * <p>Retention is failure-preserving: a bounded share of the capacity is reserved for {@code 5xx} exchanges and
 * exchanges at or above the request slow threshold ({@link RequestSlowThreshold}), so a flood of successful requests
 * cannot evict the most recent failures. Reads stay newest-first across both tiers.</p>
 *
 * <p>Writes (from any number of Vert.x event-loop threads) and the read snapshot are serialized under the tiered
 * buffer's short lock; masking and DTO assembly happen outside it in {@link HttpExchangesService} so the event loop is
 * never blocked on expensive work. The buffer is {@link IdleReclaimable}: an adapter idle tracker can
 * {@link #suspendForIdle()} to drop retained data and stop recording while the console is unused, then
 * {@link #resumeFromIdle()} to refill from live traffic.</p>
 */
public final class HttpExchangeBuffer implements IdleReclaimable, MemoryOffloadable {

    private final TieredCaptureBuffer<CapturedHttpExchange> entries;
    private final long slowThresholdMillis;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean recording = true;

    /** A buffer with the default reserved share and request slow threshold. */
    public HttpExchangeBuffer(int capacity) {
        this(capacity, TieredCaptureBuffer.DEFAULT_RESERVED_SHARE_PERCENT, RequestSlowThreshold.DEFAULT_MILLIS);
    }

    /**
     * @param capacity maximum retained exchanges, clamped to at least {@code 1}
     * @param reservedSharePercent share of the capacity reserved for failed or slow exchanges
     * @param slowThresholdMillis request slow threshold; {@code 0} disables slow classification
     */
    public HttpExchangeBuffer(int capacity, int reservedSharePercent, long slowThresholdMillis) {
        this.entries = new TieredCaptureBuffer<>(capacity, reservedSharePercent);
        this.slowThresholdMillis = Math.max(0L, slowThresholdMillis);
    }

    /** The configured maximum number of retained exchanges (clamped to at least 1). */
    public int capacity() {
        return entries.capacity();
    }

    /** The request slow threshold this buffer classifies with, {@code 0} when slow classification is disabled. */
    public long slowThresholdMillis() {
        return slowThresholdMillis;
    }

    /** Records a completed exchange, evicting per the tiered policy when at capacity. No-op while suspended. */
    public void record(CapturedHttpExchange exchange) {
        if (!recording || exchange == null) {
            return;
        }
        entries.add(
                exchange,
                RequestSlowThreshold.isFailedOrSlow(exchange.status(), exchange.durationMs(), slowThresholdMillis));
        notifyListeners();
    }

    /** Newest-first immutable snapshot, matching Actuator's reverse-chronological ordering. */
    public List<CapturedHttpExchange> snapshot() {
        return entries.newestFirst();
    }

    /** The retained exchanges, newest first, together with the retention counts that describe them. */
    public TieredCaptureBuffer.Snapshot<CapturedHttpExchange> retainedSnapshot() {
        return entries.snapshot();
    }

    /** Current retention counts, computed from the same instant as a {@link #retainedSnapshot()}. */
    public CaptureRetentionDto retention() {
        return entries.snapshot().retention(slowThresholdMillis);
    }

    @Override
    public String offloadId() {
        return "http-exchanges";
    }

    @Override
    public String offloadLabel() {
        return "HTTP exchanges";
    }

    /** Drops the retained exchanges for <b>Free BootUI memory</b>; recording, unlike {@link #suspendForIdle()}, goes on. */
    @Override
    public long offloadRetainedData() {
        long retained = entries.size();
        entries.clear();
        return retained;
    }

    @Override
    public void suspendForIdle() {
        recording = false;
        entries.clear();
    }

    @Override
    public void resumeFromIdle() {
        recording = true;
    }

    /**
     * Registers a listener invoked (with no payload) whenever a new exchange is recorded. Returns a handle
     * that removes the listener when run. Listener failures are isolated so one bad SSE subscriber cannot
     * break HTTP-exchange capture. Suspend/resume do not notify.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt HTTP-exchange capture.
            }
        }
    }
}
