package io.github.jdubois.bootui.engine.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded buffer of recently published/consumed Kafka messages.
 *
 * <p>This is a framework-neutral capture buffer, fed by the Spring adapter's {@code
 * KafkaTemplate}/listener-container post-processors (a {@code ProducerListener} for sends and a {@code
 * RecordInterceptor} for {@code @KafkaListener} consumption). It never touches a Kafka client type
 * itself, matching the same optional-dependency boundary as the other capture recorders in this
 * package (SQL trace, exceptions): the adapter parses/observes the framework-specific callback and hands
 * this recorder only primitive/neutral values.</p>
 *
 * <p><strong>Only message metadata is captured, never the message value/payload.</strong> A Kafka
 * record's value is an arbitrary, potentially large and sensitive application payload with no generic
 * masking strategy (unlike a SQL statement or a config value), so it is out of scope entirely; only a
 * short, stable hash of the key is retained, and only when {@code captureKey} is enabled. This keeps the
 * feature safe by construction rather than by best-effort redaction.</p>
 *
 * <p>Thread-safe, capped at {@code maxEntries}, and evicts the oldest message once full so it never
 * grows unbounded.</p>
 */
public final class KafkaActivityRecorder {

    /** Whether a captured message was published or consumed. */
    public enum Direction {
        PRODUCE,
        CONSUME
    }

    /** A single immutable captured Kafka message. */
    public record CapturedMessage(
            long id,
            long timestamp,
            Direction direction,
            String topic,
            Integer partition,
            Long offset,
            String key,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String groupId,
            String listenerId) {}

    private final boolean enabled;
    private final boolean captureKey;
    private final int maxEntries;
    private final int maxKeyLength;

    private final Deque<CapturedMessage> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public KafkaActivityRecorder(boolean enabled, boolean captureKey, int maxEntries, int maxKeyLength) {
        this.enabled = enabled;
        this.captureKey = captureKey;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxKeyLength = Math.max(8, maxKeyLength);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCaptureKey() {
        return captureKey;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * Records a completed (successful or failed) producer send. {@code durationMillis} is {@code
     * null} when unknown — {@code ProducerListener} carries no send-start timestamp, so producer sends
     * currently always pass {@code null} here; the parameter stays explicit so a future timing source
     * (e.g. an interceptor with a start marker) can populate it without an API change.
     */
    public void recordProduce(
            String topic, Integer partition, String key, Long durationMillis, boolean success, String errorMessage) {
        record(Direction.PRODUCE, topic, partition, null, key, durationMillis, success, errorMessage, null, null);
    }

    /** Records a completed (successful or failed) {@code @KafkaListener} record delivery. */
    public void recordConsume(
            String topic,
            Integer partition,
            Long offset,
            String key,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String groupId,
            String listenerId) {
        record(
                Direction.CONSUME,
                topic,
                partition,
                offset,
                key,
                durationMillis,
                success,
                errorMessage,
                groupId,
                listenerId);
    }

    private void record(
            Direction direction,
            String topic,
            Integer partition,
            Long offset,
            String key,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String groupId,
            String listenerId) {
        if (!enabled) {
            return;
        }
        CapturedMessage entry = new CapturedMessage(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                direction,
                topic,
                partition,
                offset,
                captureKey ? hashKey(key, maxKeyLength) : null,
                durationMillis == null ? null : Math.max(0, durationMillis),
                success,
                errorMessage,
                groupId,
                listenerId);
        synchronized (lock) {
            buffer.addLast(entry);
            // At most one entry is ever added per record() call and maxEntries is fixed at construction,
            // so the buffer can only ever be one over capacity here.
            if (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
        totalCaptured.incrementAndGet();
        notifyListeners();
    }

    /** Returns the retained messages, most recent first. */
    public List<CapturedMessage> recent() {
        synchronized (lock) {
            List<CapturedMessage> snapshot = new ArrayList<>(buffer);
            Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
        notifyListeners();
    }

    /**
     * Registers a listener invoked (with no payload) whenever a message is recorded or the buffer is
     * cleared. Returns a handle that removes the listener when run. Listener failures are isolated so
     * they cannot disrupt message production/consumption.
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
                // A misbehaving stream subscriber must never disrupt message production/consumption.
            }
        }
    }

    static String hashKey(String value) {
        return hashKey(value, 16);
    }

    static String hashKey(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            int length = Math.max(8, Math.min(hex.length(), maxLength));
            return hex.substring(0, Math.min(length, 16));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
