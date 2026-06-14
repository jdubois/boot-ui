package io.github.jdubois.bootui.autoconfigure.activity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory record of which worker thread emitted each recent Spring Security audit event,
 * captured by {@link LiveActivityController#onAuditEvent} while it handles the synchronously
 * published {@code AuditApplicationEvent} on the request thread.
 *
 * <p>Spring Security audit events carry no thread and no request path, so on their own the per-request
 * profiler can only correlate them to a request by time window and principal. That is ambiguous when
 * two concurrent requests share a principal (for example two admin calls). This registry restores
 * precision the same way the SQL and exception tiers do: a servlet request is served start-to-finish
 * on one worker thread, and the audit event is published on that very thread, so an event recorded on
 * the request's serving thread belongs unambiguously to that request — and an event recorded on a
 * <em>different</em> thread provably belongs to another request.</p>
 *
 * <p>The buffer is capped and evicts oldest-first so it never grows unbounded, mirroring BootUI's
 * other in-memory buffers.</p>
 */
public final class SecurityEventCorrelationRegistry {

    /**
     * One emitted security audit event: the worker thread it was published on, its epoch-millisecond
     * timestamp (the audit event's own timestamp, identical to the one later shown by the Security
     * Logs panel), and its type and principal used to line it up with the displayed event.
     */
    public record SecurityEventCorrelation(long millis, String thread, String type, String principal) {}

    /** Whether a displayed audit event provably belongs to the request, another request, or is unknown. */
    public enum ThreadMatch {
        /** A capture on the request's serving thread matches the event: it is exactly this request's. */
        OURS,
        /** A capture exists for this event but only on another thread: it belongs to a different request. */
        FOREIGN,
        /** No capture matches the event, so the thread tier cannot decide; fall back to time + principal. */
        UNKNOWN
    }

    private final int maxEntries;
    private final Deque<SecurityEventCorrelation> buffer = new ArrayDeque<>();
    private final Object lock = new Object();

    public SecurityEventCorrelationRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** Records one emitted audit event, evicting the oldest entry when the buffer is full. */
    public void record(SecurityEventCorrelation correlation) {
        if (correlation == null) {
            return;
        }
        synchronized (lock) {
            buffer.addLast(correlation);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * Classifies a displayed audit event against the captured records. An event matches a capture when
     * it shares the same type and its timestamp is within {@code slack} milliseconds (captures and the
     * displayed event come from the same {@code AuditEvent}, so the timestamps are effectively equal).
     * Returns {@link ThreadMatch#OURS} when any matching capture was emitted on {@code servingThread},
     * {@link ThreadMatch#FOREIGN} when matching captures exist but only on other threads, and
     * {@link ThreadMatch#UNKNOWN} when no capture matches (so the caller keeps the weaker time-window
     * match rather than dropping a real event the registry happened not to retain).
     */
    public ThreadMatch classify(String servingThread, String type, long timestamp, long slack) {
        if (servingThread == null || type == null) {
            return ThreadMatch.UNKNOWN;
        }
        boolean foreign = false;
        synchronized (lock) {
            for (SecurityEventCorrelation candidate : buffer) {
                if (!type.equalsIgnoreCase(candidate.type())) {
                    continue;
                }
                if (Math.abs(candidate.millis() - timestamp) > slack) {
                    continue;
                }
                if (servingThread.equals(candidate.thread())) {
                    return ThreadMatch.OURS;
                }
                foreign = true;
            }
        }
        return foreign ? ThreadMatch.FOREIGN : ThreadMatch.UNKNOWN;
    }

    /** Test-only snapshot of the retained records, oldest first. */
    List<SecurityEventCorrelation> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(buffer);
        }
    }
}
