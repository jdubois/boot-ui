package io.github.jdubois.bootui.engine.rabbit;

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
 * In-memory, bounded buffer of recently published/consumed AMQP (RabbitMQ) messages.
 *
 * <p>This is a framework-neutral capture buffer, fed by the Spring adapter's
 * {@code RabbitTemplate}/{@code AbstractRabbitListenerContainerFactory} post-processors (a
 * {@code MessagePostProcessor} for sends and an {@code Advice} interceptor for
 * {@code @RabbitListener} consumption) and by the Quarkus adapter's SmallRye Reactive
 * Messaging {@code OutgoingInterceptor}/{@code IncomingInterceptor} implementations. It never
 * touches a RabbitMQ client type itself, matching the same optional-dependency boundary as the
 * other capture recorders in this package (SQL trace, exceptions, Kafka): the adapter parses or
 * observes the framework-specific callback and hands this recorder only primitive/neutral
 * values.</p>
 *
 * <p><strong>Only message metadata is captured, never the message body/payload.</strong> An
 * AMQP message body is an arbitrary, potentially large and sensitive application payload with no
 * generic masking strategy, so it is out of scope entirely. The AMQP correlation ID is
 * retained in truncated, hashed form when {@code captureCorrelationId} is enabled, so it
 * cannot be used to reconstruct the original value. This keeps the feature safe by construction
 * rather than by best-effort redaction.</p>
 *
 * <p>Thread-safe, capped at {@code maxEntries}, and evicts the oldest message once full so it
 * never grows unbounded.</p>
 */
public final class RabbitActivityRecorder {

    private static final int MAX_METADATA_LENGTH = 512;
    private static final String FAILURE_MESSAGE = "Message processing failed";

    /** Whether a captured message was published or consumed. */
    public enum Direction {
        PUBLISH,
        CONSUME
    }

    /** A single immutable captured AMQP message. */
    public record CapturedMessage(
            long id,
            long timestamp,
            Direction direction,
            String exchange,
            String routingKey,
            String queue,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String correlationId) {}

    private final boolean enabled;
    private final boolean captureCorrelationId;
    private final int maxEntries;
    private final int maxCorrelationIdLength;

    private final Deque<CapturedMessage> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public RabbitActivityRecorder(
            boolean enabled, boolean captureCorrelationId, int maxEntries, int maxCorrelationIdLength) {
        this.enabled = enabled;
        this.captureCorrelationId = captureCorrelationId;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxCorrelationIdLength = Math.max(8, maxCorrelationIdLength);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCaptureCorrelationId() {
        return captureCorrelationId;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * Records a completed (or attempted) publish. {@code durationMillis} is {@code null} when
     * unknown — the Spring AMQP {@code MessagePostProcessor.postProcessMessage} hook runs just
     * before the actual {@code basicPublish} with no post-send callback available (without
     * publisher confirms), so publisher-side durations currently always pass {@code null};
     * the parameter stays explicit so a future timing source can populate it without an API
     * change.
     */
    public void recordPublish(
            String exchange,
            String routingKey,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String correlationId) {
        record(Direction.PUBLISH, exchange, routingKey, null, durationMillis, success, errorMessage, correlationId);
    }

    /** Records a completed (successful or failed) {@code @RabbitListener} message delivery. */
    public void recordConsume(
            String exchange,
            String routingKey,
            String queue,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String correlationId) {
        record(Direction.CONSUME, exchange, routingKey, queue, durationMillis, success, errorMessage, correlationId);
    }

    private void record(
            Direction direction,
            String exchange,
            String routingKey,
            String queue,
            Long durationMillis,
            boolean success,
            String errorMessage,
            String correlationId) {
        if (!enabled) {
            return;
        }
        CapturedMessage entry = new CapturedMessage(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                direction,
                truncate(exchange),
                truncate(routingKey),
                truncate(queue),
                durationMillis == null ? null : Math.max(0, durationMillis),
                success,
                success ? null : FAILURE_MESSAGE,
                captureCorrelationId ? hashCorrelationId(correlationId, maxCorrelationIdLength) : null);
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
     * Registers a listener invoked (with no payload) whenever a message is recorded or the
     * buffer is cleared. Returns a handle that removes the listener when run. Listener failures
     * are isolated so they cannot disrupt message production/consumption.
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
                // A misbehaving stream subscriber must never disrupt message publication/consumption.
            }
        }
    }

    static String hashCorrelationId(String value) {
        return hashCorrelationId(value, 16);
    }

    static String hashCorrelationId(String value, int maxLength) {
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
            return hex.substring(0, length);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_METADATA_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_METADATA_LENGTH);
    }
}
