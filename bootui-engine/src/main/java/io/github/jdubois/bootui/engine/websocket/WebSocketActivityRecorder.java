package io.github.jdubois.bootui.engine.websocket;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, independently bounded buffer of recent WebSocket frame and connection-lifecycle
 * activity.
 *
 * <p><strong>Metadata only.</strong> Adapters call {@link #recordFrame} with a frame's kind, direction,
 * destination, and byte count — never its bytes or text, never its headers, never a principal, and never a
 * query value. The recorder additionally maintains per-session counters so the panel can show how busy a
 * session is without retaining anything about what was sent.</p>
 *
 * <p>The buffer, the per-session counter map, and the panel's endpoint/session/subscription caps are all
 * independent, so a WebSocket burst can neither evict another panel's data nor push endpoints out of the
 * report. Every entry point is fail-open: recording is best-effort and must never disrupt, delay, or
 * reorder application frame dispatch.</p>
 */
public final class WebSocketActivityRecorder implements IdleReclaimable, RuntimeEventPublisher, MemoryOffloadable {

    /** Frame direction relative to the application. */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /** Frame or lifecycle event kind. */
    public enum FrameType {
        TEXT,
        BINARY,
        PING,
        PONG,
        OPEN,
        CLOSE,
        CONNECT,
        SUBSCRIBE,
        UNSUBSCRIBE
    }

    /** One captured frame or lifecycle event. {@code sessionId} is already opaque. */
    public record CapturedFrame(
            long id,
            long timestamp,
            String endpointId,
            String sessionId,
            Direction direction,
            FrameType frameType,
            String destination,
            Long payloadBytes,
            Long durationMillis,
            boolean success,
            String errorCategory) {}

    /** Per-session counters. All values are counts and sizes; no payload is retained. */
    public record SessionCounters(long messagesIn, long messagesOut, long bytesIn, long bytesOut, long lastActivityAt) {

        static SessionCounters empty() {
            return new SessionCounters(0, 0, 0, 0, 0);
        }
    }

    private static final int MAX_DESTINATION_LENGTH = 256;
    private static final int MAX_ERROR_CATEGORY_LENGTH = 120;

    private final boolean enabled;
    private final int maxEntries;
    private final int maxTrackedSessions;

    private final Deque<CapturedFrame> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Map<String, SessionCounters> sessionCounters;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong inboundFrames = new AtomicLong();
    private final AtomicLong outboundFrames = new AtomicLong();
    private final AtomicLong inboundBytes = new AtomicLong();
    private final AtomicLong outboundBytes = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final AtomicBoolean capturing;
    private volatile boolean idleSuspended;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    public WebSocketActivityRecorder(WebSocketSettings settings) {
        this.enabled = settings.enabled();
        this.capturing = new AtomicBoolean(settings.enabled() && settings.capturing());
        this.maxEntries = settings.maxActivityEntries();
        this.maxTrackedSessions = settings.maxTrackedSessions();
        this.sessionCounters = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SessionCounters> eldest) {
                return size() > WebSocketActivityRecorder.this.maxTrackedSessions;
            }
        };
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each application message a handler
     * ran. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    /**
     * Publishes one inbound application message a handler ran, as the entry of the execution {@code context} names
     * ({@code docs/PLAN-v2.md} §5.18, M4-10). The adapter opens that execution around the handler, so the SQL,
     * exceptions, and calls the handler made nest under it. It is published whether or not the panel is capturing
     * frames, since pausing the panel's frame log does not pause the journal.
     *
     * @param destination the destination as a template, such as {@code /app/chat/{room}}, or the endpoint's path
     * @param payloadBytes the payload's size in bytes, when known without reading it
     */
    public void recordHandledMessage(
            String endpoint,
            String destination,
            Long payloadBytes,
            long durationNanos,
            boolean failed,
            CorrelationContext context) {
        if (!enabled || context == null || context.executionId() == null) {
            return;
        }
        try {
            journal.offer(RuntimeEvent.of(
                    JournalSource.WEBSOCKET,
                    RuntimeEvent.startMillis(System.currentTimeMillis(), durationNanos),
                    durationNanos,
                    context,
                    Thread.currentThread().getName(),
                    null,
                    failed,
                    WebSocketPayload.handled(
                            endpoint, truncate(destination, MAX_DESTINATION_LENGTH), payloadBytes, failed)));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the message it observes.
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCapturing() {
        return enabled && capturing.get() && !idleSuspended;
    }

    /** Pauses or resumes capture at runtime without uninstalling the adapter's capture bindings. */
    public void setCapturing(boolean value) {
        if (capturing.getAndSet(value) != value) {
            notifyListeners();
        }
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * Records one frame or lifecycle event. {@code rawSessionId} is hashed before storage, so the raw
     * framework identifier never enters the buffer.
     */
    public void recordFrame(
            String endpointId,
            String rawSessionId,
            Direction direction,
            FrameType frameType,
            String destination,
            Long payloadBytes,
            Long durationMillis,
            boolean success,
            String errorCategory) {
        if (!isCapturing() || direction == null || frameType == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long bytes = payloadBytes == null ? 0 : Math.max(0, payloadBytes);
        String opaqueSessionId = WebSocketSessionIds.opaque(rawSessionId);
        CapturedFrame entry = new CapturedFrame(
                sequence.incrementAndGet(),
                now,
                endpointId,
                opaqueSessionId,
                direction,
                frameType,
                truncate(redactSessionId(destination, rawSessionId), MAX_DESTINATION_LENGTH),
                payloadBytes == null ? null : bytes,
                durationMillis == null ? null : Math.max(0, durationMillis),
                success,
                success ? null : truncate(errorCategory, MAX_ERROR_CATEGORY_LENGTH));
        synchronized (lock) {
            buffer.addLast(entry);
            if (buffer.size() > maxEntries) {
                buffer.removeFirst();
                evicted.incrementAndGet();
            }
            if (opaqueSessionId != null) {
                SessionCounters current = sessionCounters.getOrDefault(opaqueSessionId, SessionCounters.empty());
                sessionCounters.put(
                        opaqueSessionId,
                        new SessionCounters(
                                current.messagesIn() + (direction == Direction.INBOUND ? 1 : 0),
                                current.messagesOut() + (direction == Direction.OUTBOUND ? 1 : 0),
                                current.bytesIn() + (direction == Direction.INBOUND ? bytes : 0),
                                current.bytesOut() + (direction == Direction.OUTBOUND ? bytes : 0),
                                now));
            }
        }
        totalCaptured.incrementAndGet();
        if (direction == Direction.INBOUND) {
            inboundFrames.incrementAndGet();
            inboundBytes.addAndGet(bytes);
        } else {
            outboundFrames.incrementAndGet();
            outboundBytes.addAndGet(bytes);
        }
        if (!success) {
            failedFrames.incrementAndGet();
        }
        notifyListeners();
    }

    /** Counters observed for {@code rawSessionId}, or empty counters when the session is not tracked. */
    public SessionCounters counters(String rawSessionId) {
        if (rawSessionId == null) {
            return SessionCounters.empty();
        }
        String opaqueSessionId = WebSocketSessionIds.opaque(rawSessionId);
        synchronized (lock) {
            return sessionCounters.getOrDefault(opaqueSessionId, SessionCounters.empty());
        }
    }

    /** Retained activity, most recent first. */
    public List<CapturedFrame> recent() {
        synchronized (lock) {
            List<CapturedFrame> snapshot = new ArrayList<>(buffer);
            Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return evicted.get();
    }

    public long inboundFrames() {
        return inboundFrames.get();
    }

    public long outboundFrames() {
        return outboundFrames.get();
    }

    public long inboundBytes() {
        return inboundBytes.get();
    }

    public long outboundBytes() {
        return outboundBytes.get();
    }

    public long failedFrames() {
        return failedFrames.get();
    }

    @Override
    public String offloadId() {
        return "websockets";
    }

    @Override
    public String offloadLabel() {
        return "WebSocket frames";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = recent().size();
        clear();
        return retained;
    }

    /** Drops the retained activity and per-session counters. Never touches a live session. */
    public void clear() {
        synchronized (lock) {
            buffer.clear();
            sessionCounters.clear();
        }
        notifyListeners();
    }

    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        synchronized (lock) {
            buffer.clear();
            sessionCounters.clear();
        }
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A stream subscriber must never disrupt WebSocket frame dispatch.
            }
        }
    }

    /**
     * Removes a raw framework session identifier that a destination may embed.
     *
     * <p>Spring's user-destination resolver rewrites a message for {@code /user/{name}/queue/x} into the
     * broker destination {@code /queue/x-user<simpSessionId>}, so the destination carries verbatim the one
     * value BootUI hashes everywhere else — the identifier a frame needs to target a live session. Redacting
     * it here, at the single choke point every adapter goes through, means no adapter can reintroduce the
     * leak by forgetting to sanitize.</p>
     */
    private static String redactSessionId(String destination, String rawSessionId) {
        if (destination == null || rawSessionId == null || rawSessionId.isEmpty()) {
            return destination;
        }
        return destination.contains(rawSessionId) ? destination.replace(rawSessionId, "{session}") : destination;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
