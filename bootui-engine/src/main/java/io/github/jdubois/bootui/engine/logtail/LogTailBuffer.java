package io.github.jdubois.bootui.engine.logtail;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Framework-neutral capped ring buffer behind the Log Tail panel, shared by the Spring Boot and Quarkus
 * adapters. The Spring adapter feeds it from a Logback appender; the Quarkus adapter feeds it from a
 * {@code java.util.logging} handler. Both adapters expose the identical {@code /recent} + SSE
 * {@code /stream} wire on top, so the single shared Vue panel renders the same on every platform.
 *
 * <p>The buffer is bounded twice: by line count and by an approximate byte budget (UTF-8 length of the
 * message + thread of each retained line). Either bound evicting oldest-first keeps the buffer small on a
 * long-lived dev process, while the byte budget defaults high enough that a normal {@link #DEFAULT_MAX_LINES}
 * tail of short lines never trips it (so the Spring wire stays byte-identical). At least one line is
 * always retained even if a single line exceeds the byte budget.</p>
 *
 * <p><strong>Lost-line race.</strong> A naive "snapshot recent, then subscribe" stream drops every line
 * appended between the two calls. {@link #subscribeWithReplay(Consumer)} closes that window: it snapshots
 * the backlog and registers the subscriber under one lock, so live delivery begins exactly where the
 * snapshot ends — no gap, no duplicate.</p>
 *
 * <p><strong>Re-entrancy.</strong> Subscribers (and any code they call) may log; that would re-enter
 * {@link #add} on the logging thread. A {@link ThreadLocal} guard drops re-entrant appends so the tail
 * can never recurse into a stack overflow. Subscribers are notified <em>outside</em> the lock, and the
 * buffer itself never logs.</p>
 *
 * <p><strong>Delivery threads.</strong> Streams hand captured lines to threads made by
 * {@link #deliveryThreadFactory(String)}, which expose and write them to clients. Lines logged on those
 * threads, such as a framework's debug output about encoding the stream itself, are never captured. This
 * extends the re-entrancy guard across the hand-off: otherwise each delivered line could log a new line
 * that is captured and delivered in turn, without end.</p>
 */
public final class LogTailBuffer {

    /** Default line cap; matches the historical Spring Logback ring depth so its wire is byte-identical. */
    public static final int DEFAULT_MAX_LINES = 500;

    private static final ThreadLocal<Boolean> DELIVERY_THREAD = BootUiThreadLocal.withInitial(() -> Boolean.FALSE);

    private final int maxLines;
    private final long maxBytes;

    private final ArrayDeque<Entry> lines;
    private final CopyOnWriteArrayList<Consumer<LogLineDto>> subscribers = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Boolean> appending = BootUiThreadLocal.withInitial(() -> Boolean.FALSE);
    private long totalBytes;

    public LogTailBuffer() {
        this(DEFAULT_MAX_LINES, Long.MAX_VALUE);
    }

    /**
     * @param maxLines maximum retained lines (clamped to at least 1)
     * @param maxBytes approximate retained-byte budget; non-positive means unbounded
     */
    public LogTailBuffer(int maxLines, long maxBytes) {
        this.maxLines = Math.max(1, maxLines);
        this.maxBytes = maxBytes <= 0 ? Long.MAX_VALUE : maxBytes;
        this.lines = new ArrayDeque<>(this.maxLines);
    }

    /** The approximate retained-byte budget; {@link Long#MAX_VALUE} when unbounded. */
    public long maxBytes() {
        return maxBytes;
    }

    /** Appends a line, evicting oldest lines past the line/byte caps, then notifies subscribers. */
    public void add(LogLineDto line) {
        if (line == null || Boolean.TRUE.equals(appending.get()) || Boolean.TRUE.equals(DELIVERY_THREAD.get())) {
            return;
        }
        appending.set(Boolean.TRUE);
        try {
            int size = sizeOf(line);
            synchronized (lines) {
                lines.addLast(new Entry(line, size));
                totalBytes += size;
                while (lines.size() > maxLines || (totalBytes > maxBytes && lines.size() > 1)) {
                    totalBytes -= lines.removeFirst().bytes();
                }
            }
            for (Consumer<LogLineDto> subscriber : subscribers) {
                subscriber.accept(line);
            }
        } finally {
            appending.set(Boolean.FALSE);
        }
    }

    /** A snapshot of the currently retained lines, oldest first. */
    public List<LogLineDto> recent() {
        synchronized (lines) {
            List<LogLineDto> snapshot = new ArrayList<>(lines.size());
            for (Entry entry : lines) {
                snapshot.add(entry.line());
            }
            return snapshot;
        }
    }

    /**
     * Atomically captures the current backlog and registers a live subscriber under one lock, so no line
     * is dropped or duplicated between replay and live delivery. Returns the backlog plus an unsubscribe
     * handle; the caller replays the backlog and then forwards live lines from the subscriber.
     */
    public Subscription subscribeWithReplay(Consumer<LogLineDto> subscriber) {
        synchronized (lines) {
            List<LogLineDto> backlog = new ArrayList<>(lines.size());
            for (Entry entry : lines) {
                backlog.add(entry.line());
            }
            subscribers.add(subscriber);
            return new Subscription(backlog, () -> subscribers.remove(subscriber));
        }
    }

    private static int sizeOf(LogLineDto line) {
        int bytes = 0;
        if (line.message() != null) {
            bytes += line.message().getBytes(StandardCharsets.UTF_8).length;
        }
        if (line.thread() != null) {
            bytes += line.thread().getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes;
    }

    private record Entry(LogLineDto line, int bytes) {}

    /**
     * A factory for the daemon threads that deliver captured lines to log-tail clients, named {@code namePrefix}
     * followed by a sequence number. No {@code LogTailBuffer} captures a line logged on one of these threads.
     */
    public static ThreadFactory deliveryThreadFactory(String namePrefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(
                    () -> {
                        DELIVERY_THREAD.set(Boolean.TRUE);
                        runnable.run();
                    },
                    namePrefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Whether the calling thread delivers log-tail lines, so anything it logs is not captured. */
    public static boolean isDeliveryThread() {
        return Boolean.TRUE.equals(DELIVERY_THREAD.get());
    }

    /** The number of live subscribers, so adapters and tests can confirm a closed stream released its subscription. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** Backlog snapshot plus an unsubscribe handle returned by {@link #subscribeWithReplay}. */
    public record Subscription(List<LogLineDto> backlog, Runnable unsubscribe) {}
}
