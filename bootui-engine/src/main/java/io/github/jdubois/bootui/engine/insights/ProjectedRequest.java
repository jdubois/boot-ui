package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * One completed request as the journal retains it ({@code docs/PLAN-v2.md} §5.4): its route, outcome, and every child
 * event that carries its request id, in the order the journal recorded them. Every child belongs to it by identity, so
 * the request's evidence is exact at the {@code REQUEST_ID} tier.
 *
 * @param requestId the request's id
 * @param route its route label, such as {@code GET /api/orders/{id}}
 * @param method its HTTP method
 * @param path its decoded path
 * @param status its response status
 * @param startMillis when it started, in epoch milliseconds
 * @param durationNanos how long it took
 * @param children its child events, in sequence order
 * @param timing its monotonic start and phases, or {@code null} when unknown
 * @param resources its measured CPU time and allocation, or {@code null} when not measured
 * @param traceId its distributed-trace id, or {@code null} without tracing
 * @param thread the thread its HTTP event was recorded on, or {@code null}
 */
public record ProjectedRequest(
        String requestId,
        String route,
        String method,
        String path,
        int status,
        long startMillis,
        long durationNanos,
        List<RuntimeEvent> children,
        RequestTiming timing,
        ResourceUsage resources,
        String traceId,
        String thread) {

    public ProjectedRequest {
        children = List.copyOf(children);
    }

    /** The children of one source, in sequence order. */
    public List<RuntimeEvent> children(JournalSource source) {
        List<RuntimeEvent> matching = new ArrayList<>();
        for (RuntimeEvent child : children) {
            if (child.source() == source) {
                matching.add(child);
            }
        }
        return matching;
    }

    /** Whether the request's method is safe in the HTTP sense, GET or HEAD. */
    public boolean safeMethod() {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }
}
