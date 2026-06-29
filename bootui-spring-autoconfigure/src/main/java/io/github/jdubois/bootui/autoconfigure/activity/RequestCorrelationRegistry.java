package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.idle.IdleReclaimable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory record of which worker thread served each recent HTTP request, captured by
 * {@link RequestCorrelationFilter}.
 *
 * <p>It exists so the per-request profiler can correlate JDBC statements to a request <em>exactly</em>
 * even when no distributed trace id is available: a servlet request is processed start-to-finish on a
 * single worker thread, and that thread serves only one request at a time, so any statement recorded
 * on the same thread inside the request's window belongs unambiguously to that request.</p>
 *
 * <p>The buffer is capped and evicts oldest-first so it never grows unbounded, mirroring BootUI's
 * other in-memory buffers.</p>
 */
public final class RequestCorrelationRegistry implements IdleReclaimable {

    /**
     * One served request: the worker thread and the wall-clock window during which the request was
     * handled. {@code path} is the servlet request URI (no query string), matched against an HTTP
     * exchange's path.
     */
    public record RequestCorrelation(long startMillis, long endMillis, String thread, String method, String path) {}

    private final int maxEntries;
    private final Deque<RequestCorrelation> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile boolean idleSuspended = false;

    public RequestCorrelationRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** Records one served request, evicting the oldest entry when the buffer is full. */
    public void record(RequestCorrelation correlation) {
        if (correlation == null || idleSuspended) {
            return;
        }
        synchronized (lock) {
            buffer.addLast(correlation);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        synchronized (lock) {
            buffer.clear();
        }
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Returns the single request whose handling window overlaps {@code [start, end]} for the given
     * method and path, or {@code null} when there is no match or more than one candidate. Requiring a
     * <em>unique</em> candidate is deliberate: two genuinely concurrent identical requests have
     * overlapping windows, so this returns {@code null} and the caller safely falls back to a coarser
     * heuristic rather than risk attributing one request's SQL to the other.
     */
    public RequestCorrelation match(String method, String path, long start, long end) {
        if (method == null || path == null) {
            return null;
        }
        long slack = 50L;
        RequestCorrelation found = null;
        synchronized (lock) {
            for (RequestCorrelation candidate : buffer) {
                if (!method.equalsIgnoreCase(candidate.method()) || !path.equals(candidate.path())) {
                    continue;
                }
                if (candidate.startMillis() > end + slack || candidate.endMillis() < start - slack) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }

    /** Test-only snapshot of the retained records, oldest first. */
    List<RequestCorrelation> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(buffer);
        }
    }
}
