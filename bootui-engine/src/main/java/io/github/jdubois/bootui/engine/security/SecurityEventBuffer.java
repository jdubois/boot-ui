package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capped, thread-safe ring buffer of {@link CapturedSecurityEvent} records — the Quarkus capture source
 * for the Security Logs panel. Spring keeps Actuator's {@code AuditEventRepository} as its source of
 * truth, so this buffer is wired only on Quarkus, where there is no Actuator analogue.
 *
 * <p>Writes (from Quarkus CDI security-event observers, often on the Vert.x event loop) and the read
 * snapshot are serialized under a single short lock; masking and DTO assembly happen outside the lock in
 * {@link SecurityLogsService} so the event loop is never blocked. The buffer is {@link IdleReclaimable}:
 * an adapter idle tracker can {@link #suspendForIdle()} to drop retained data and stop retaining events
 * while the console is unused (the runtime journal keeps receiving them), then {@link #resumeFromIdle()} to refill
 * from live events.
 */
public final class SecurityEventBuffer implements IdleReclaimable, RuntimeEventPublisher {

    private final int capacity;
    private final ArrayDeque<CapturedSecurityEvent> entries;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean recording = true;
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    public SecurityEventBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded security event right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    /**
     * Records a captured event, evicting the oldest when at capacity. While suspended for idleness the buffer retains
     * nothing, but the runtime journal still receives the event when it records security events ({@code
     * docs/PLAN-v2.md} §5.2).
     */
    public void record(CapturedSecurityEvent event) {
        record(event, event == null ? null : CorrelationContext.forRequest(event.requestId()));
    }

    /**
     * Publishes an authorization decision to the runtime journal's {@code authorization} source ({@code docs/PLAN-v2.md}
     * §5.18), observed under {@code context}. Decisions are never retained here: a decision fires per check, and would
     * evict the failures this buffer keeps for review.
     */
    public void recordAuthorization(
            AuthorizationPayload decision, long epochMillis, CorrelationContext context, String thread) {
        RuntimeEventSink sink = journal;
        if (decision == null || !sink.records(JournalSource.AUTHORIZATION)) {
            return;
        }
        sink.offer(RuntimeEvent.of(
                JournalSource.AUTHORIZATION,
                epochMillis,
                0,
                context == null ? CorrelationContext.NONE : context,
                thread,
                null,
                !decision.granted(),
                decision));
    }

    /**
     * Records a captured event observed under {@code context}, the correlation current where the adapter observed it,
     * so its journal event carries the request, execution, and trace that context names.
     */
    public void record(CapturedSecurityEvent event, CorrelationContext context) {
        if (event == null) {
            return;
        }
        boolean retain = recording;
        if (!retain && !journal.records(JournalSource.SECURITY)) {
            return;
        }
        if (retain) {
            synchronized (entries) {
                if (entries.size() >= capacity) {
                    entries.pollFirst();
                }
                entries.addLast(event);
            }
        }
        CorrelationContext correlation = context == null ? CorrelationContext.forRequest(event.requestId()) : context;
        SecurityJournal.publish(journal, event.type(), event.timestamp(), correlation, event.traceId());
        if (retain) {
            notifyListeners();
        }
    }

    /** Newest-first immutable snapshot, matching Actuator's reverse-chronological ordering. */
    public List<CapturedSecurityEvent> snapshot() {
        List<CapturedSecurityEvent> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        List<CapturedSecurityEvent> reversed = new ArrayList<>(copy.size());
        for (int i = copy.size() - 1; i >= 0; i--) {
            reversed.add(copy.get(i));
        }
        return List.copyOf(reversed);
    }

    @Override
    public void suspendForIdle() {
        recording = false;
        synchronized (entries) {
            entries.clear();
        }
    }

    @Override
    public void resumeFromIdle() {
        recording = true;
    }

    /**
     * Registers a listener invoked (with no payload) whenever a new event is recorded. Returns a handle
     * that removes the listener when run. Listener failures are isolated so one bad SSE subscriber cannot
     * break security-event capture. Suspend/resume do not notify.
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
                // A misbehaving stream subscriber must never disrupt security-event capture.
            }
        }
    }
}
