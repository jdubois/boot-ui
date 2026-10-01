package io.github.jdubois.bootui.engine.jms;

import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
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
 * Framework-neutral, bounded buffer of recently sent and consumed JMS messages.
 *
 * <p>The Spring adapter feeds this recorder from its {@code JmsTemplate} and listener-container
 * interception seams. The recorder itself uses only neutral values and never imports a JMS or
 * Spring type, keeping the optional dependency confined to the adapter.
 *
 * <p><strong>Only metadata is captured, never the message body or arbitrary properties.</strong>
 * Provider-assigned message IDs are retained only as truncated SHA-256 hashes when enabled.
 */
public final class JmsActivityRecorder implements RuntimeEventPublisher {

    private static final int MAX_METADATA_LENGTH = 200;

    public enum Direction {
        PRODUCE,
        CONSUME
    }

    public record CapturedMessage(
            long id,
            long timestamp,
            Direction direction,
            String destination,
            String messageId,
            Long durationMillis,
            boolean success,
            String failureType,
            String subscriptionName,
            String listenerId,

            String requestId,

            String executionId) {

        /** Without BootUI's execution identity. */
        public CapturedMessage(
                long id,
                long timestamp,
                Direction direction,
                String destination,
                String messageId,
                Long durationMillis,
                boolean success,
                String failureType,
                String subscriptionName,
                String listenerId,
                String requestId) {

            this(
                    id,
                    timestamp,
                    direction,
                    destination,
                    messageId,
                    durationMillis,
                    success,
                    failureType,
                    subscriptionName,
                    listenerId,
                    requestId,
                    null);
        }

        /** Without BootUI's execution identity. */
        public CapturedMessage(
                long id,
                long timestamp,
                Direction direction,
                String destination,
                String messageId,
                Long durationMillis,
                boolean success,
                String failureType,
                String subscriptionName,
                String listenerId) {

            this(
                    id,
                    timestamp,
                    direction,
                    destination,
                    messageId,
                    durationMillis,
                    success,
                    failureType,
                    subscriptionName,
                    listenerId,
                    null);
        }
    }

    private final boolean enabled;
    private final boolean captureMessageId;
    private final int maxEntries;
    private final int maxMessageIdLength;

    private final Deque<CapturedMessage> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final CorrelationSource correlation = new CorrelationSource();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public JmsActivityRecorder(boolean enabled, boolean captureMessageId, int maxEntries, int maxMessageIdLength) {
        this.enabled = enabled;
        this.captureMessageId = captureMessageId;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxMessageIdLength = Math.max(8, maxMessageIdLength);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCaptureMessageId() {
        return captureMessageId;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public int getMaxMessageIdLength() {
        return maxMessageIdLength;
    }

    /**
     * Replaces the source of the correlation stamped on each message ({@code docs/PLAN-v2.md} §5.1): the sender's
     * request or execution for an outgoing message, and the listener's own execution for a consumed one. Defaults to
     * the thread's correlation scope; passing {@code null} restores it.
     */
    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded message right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public void setCorrelationContextProvider(CorrelationContextProvider correlationProvider) {
        correlation.set(correlationProvider);
    }

    public void recordProduce(
            String destination, String messageId, Long durationMillis, boolean success, String failureType) {
        record(
                Direction.PRODUCE,
                destination,
                messageId,
                durationMillis,
                success,
                failureType,
                null,
                null,
                correlation.current());
    }

    public void recordConsume(
            String destination,
            String messageId,
            Long durationMillis,
            boolean success,
            String failureType,
            String subscriptionName,
            String listenerId) {
        record(
                Direction.CONSUME,
                destination,
                messageId,
                durationMillis,
                success,
                failureType,
                subscriptionName,
                listenerId,
                correlation.current());
    }

    private void record(
            Direction direction,
            String destination,
            String messageId,
            Long durationMillis,
            boolean success,
            String failureType,
            String subscriptionName,
            String listenerId,
            CorrelationContext context) {
        if (!enabled) {
            return;
        }
        CapturedMessage entry = new CapturedMessage(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                direction,
                truncate(destination),
                captureMessageId ? hashMessageId(messageId, maxMessageIdLength) : null,
                durationMillis == null ? null : Math.max(0, durationMillis),
                success,
                success ? null : truncate(failureType),
                truncate(subscriptionName),
                truncate(listenerId),
                context.requestId(),
                context.executionId());
        synchronized (lock) {
            buffer.addLast(entry);
            if (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
        boolean sent = direction == Direction.PRODUCE;
        journal.offer(new RuntimeEvent(
                JournalSource.MESSAGING,
                entry.timestamp(),
                entry.durationMillis() == null ? -1 : entry.durationMillis() * 1_000_000,
                context.requestId(),
                context.executionId(),
                context.traceId(),
                context.spanId(),
                sent ? null : Thread.currentThread().getName(),
                null,
                !success,
                new MessagingPayload("jms", sent, entry.destination(), !success)));
        totalCaptured.incrementAndGet();
        notifyListeners();
    }

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

    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A stream subscriber must never disrupt message production or consumption.
            }
        }
    }

    static String hashMessageId(String value) {
        return hashMessageId(value, 16);
    }

    static String hashMessageId(String value, int maxLength) {
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
