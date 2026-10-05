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
 * <p>A scheduled run or a consumed message is projected the same way (M3-8), with its execution id as its id and a name
 * like a route's, such as {@code @Scheduled OrderJob.run} or {@code consume kafka:orders}, so the observations that
 * read a request's own work also cover jobs and listeners. It has no method, path, status, or phases.</p>
 *
 * @param requestId the request's id, or the execution's
 * @param route its route label, such as {@code GET /api/orders/{id}}, or the execution's name
 * @param method its HTTP method, or {@code null} for an execution
 * @param path its decoded path, or {@code null} for an execution
 * @param status its response status, or {@code 0} for an execution
 * @param startMillis when it started, in epoch milliseconds
 * @param durationNanos how long it took
 * @param children its child events, in sequence order
 * @param timing its monotonic start and phases, or {@code null} when unknown
 * @param resources its measured CPU time and allocation, or {@code null} when not measured
 * @param traceId its distributed-trace id, or {@code null} without tracing
 * @param thread the thread its HTTP event was recorded on, or {@code null}
 * @param kind whether it is an HTTP request, a scheduled run, or a consumed message
 * @param failed whether it ended in failure: a 5xx response, or an exception that escaped its scheduled run or message
 *     handler, so nothing caught it
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
        String thread,
        Kind kind,
        boolean failed) {

    public ProjectedRequest {
        children = List.copyOf(children);
        kind = kind == null ? Kind.HTTP : kind;
    }

    /** Whether it is an HTTP request rather than a scheduled run or a consumed message. */
    public boolean http() {
        return kind == Kind.HTTP;
    }

    /** What a projected unit of work is. */
    public enum Kind {
        /** An HTTP request. */
        HTTP,
        /** A {@code @Scheduled} run. */
        SCHEDULED,
        /** A consumed message. */
        MESSAGE
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
