package io.github.jdubois.bootui.autoconfigure.reactive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory record of the distributed-trace id active when each recent Spring Security audit
 * event was published on the reactive (WebFlux) adapter, captured by
 * {@link ReactiveSecurityLogsController#onApplicationEvent} while it observes the synchronously-published
 * {@code AuditApplicationEvent}.
 *
 * <p>Reactive sibling of {@code SecurityEventCorrelationRegistry}: Spring Security audit events carry no
 * thread, request path, or trace id, so on WebFlux - which has no serving-thread invariant to fall back
 * on - the only correlation signal available is the trace id read from {@link
 * io.github.jdubois.bootui.spi.TraceIdProvider} at the moment of publication, the same signal {@code
 * ReactiveHttpExchangeTraceFilter} and {@code SqlTraceRecorder} capture from. Matched by type + principal
 * + timestamp window, mirroring {@code SecurityEventCorrelationRegistry}'s matching rule (captures and
 * the displayed event originate from the very same {@code AuditEvent}, so the timestamps are effectively
 * equal) but requiring a <em>unique</em> candidate, exactly like {@code RequestCorrelationRegistry}.</p>
 *
 * <p>The buffer is capped and evicts oldest-first so it never grows unbounded.</p>
 */
public final class ReactiveSecurityEventTraceRegistry {

    /** One published audit event: its epoch-millisecond timestamp, type, principal, and captured trace id. */
    public record SecurityEventTrace(long millis, String type, String principal, String traceId) {}

    private final int maxEntries;
    private final Deque<SecurityEventTrace> buffer = new ArrayDeque<>();
    private final Object lock = new Object();

    public ReactiveSecurityEventTraceRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Records one published audit event's captured trace id, evicting the oldest entry when the buffer
     * is full. A blank/missing trace id is not recorded at all - there is nothing useful to correlate
     * later.
     */
    public void record(SecurityEventTrace trace) {
        if (trace == null || trace.traceId() == null || trace.traceId().isBlank()) {
            return;
        }
        synchronized (lock) {
            buffer.addLast(trace);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * Returns the trace id captured for the single audit event matching {@code type}/{@code principal}
     * within {@code slack} milliseconds of {@code timestamp}, or {@code null} when there is no match or
     * more than one candidate.
     */
    public String match(String type, String principal, long timestamp) {
        if (type == null) {
            return null;
        }
        long slack = 50L;
        SecurityEventTrace found = null;
        synchronized (lock) {
            for (SecurityEventTrace candidate : buffer) {
                if (!type.equalsIgnoreCase(candidate.type()) || !principalMatches(principal, candidate.principal())) {
                    continue;
                }
                if (Math.abs(candidate.millis() - timestamp) > slack) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
        }
        return found == null ? null : found.traceId();
    }

    private boolean principalMatches(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        return left != null && left.equalsIgnoreCase(right);
    }

    /** Test-only snapshot of the retained records, oldest first. */
    List<SecurityEventTrace> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(buffer);
        }
    }
}
