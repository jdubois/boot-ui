package io.github.jdubois.bootui.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Measures how much request-thread work Live Activity attaches to the request that produced it.
 *
 * <p>This is the baseline for {@code docs/PLAN-v2.md} §5.1 (exact correlation). It reads only the public Live
 * Activity feed, so the same measurement runs unchanged on Spring MVC, Spring WebFlux, and Quarkus, and keeps
 * measuring the same thing once capture-time request ids replace today's trace-id, serving-thread, and
 * time-window tiers.</p>
 *
 * <p>Only entries inside the measured window count. A child entry counts as request-thread work when its thread
 * matches the runner's request-serving thread pattern. Entries without a thread cannot be classified that way:
 * they are counted separately and included, because a scenario runs no background traffic of its own. A child is
 * <em>nested</em> when its {@code parentId} names a {@code REQUEST} entry of the window. A consumed message
 * ({@code MESSAGING} whose summary starts with {@code ←}) anchors its own execution, so it is not request work and is
 * left out.</p>
 *
 * <p>A child is <em>misattributed</em> when it carries a thread and is nested under a request whose interval, widened
 * by {@link #INTERVAL_SLACK_MILLIS} for millisecond rounding, does not contain it: the signature of a context that
 * leaked from an earlier request. Children that report without a thread, such as a message send acknowledged later on
 * the broker client's I/O thread, are not checked, because their timestamp is the acknowledgement's.</p>
 */
public final class CorrelationCoverage {

    /** Child types whose request-thread entries a request should own, in report order. */
    public static final List<String> CHILD_TYPES =
            List.of("SQL", "SECURITY", "EXCEPTION", "CACHE", "REST_CLIENT", "MESSAGING", "MAIL", "FAULT_TOLERANCE");

    /** Widening of a request's interval when checking a nested child's timestamp, for millisecond rounding. */
    static final long INTERVAL_SLACK_MILLIS = 5;

    private CorrelationCoverage() {}

    /** The Live Activity fields this measurement reads. */
    public record Entry(
            String id,
            String type,
            long timestamp,
            String correlationId,
            String thread,
            String parentId,
            Integer status,
            String summary,
            Long durationMs) {

        public Entry(
                String id,
                String type,
                long timestamp,
                String correlationId,
                String thread,
                String parentId,
                Integer status) {
            this(id, type, timestamp, correlationId, thread, parentId, status, null, null);
        }

        public static Entry fromJson(JsonNode node) {
            JsonNode status = node.get("status");
            JsonNode duration = node.get("durationMs");
            return new Entry(
                    text(node, "id"),
                    text(node, "type"),
                    node.path("timestamp").asLong(),
                    text(node, "correlationId"),
                    text(node, "thread"),
                    text(node, "parentId"),
                    status == null || status.isNull() ? null : status.asInt(),
                    text(node, "summary"),
                    duration == null || duration.isNull() ? null : duration.asLong());
        }

        boolean consumedMessage() {
            return "MESSAGING".equals(type) && summary != null && summary.startsWith("←");
        }

        boolean hasThread() {
            return thread != null && !thread.isBlank();
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
    }

    /**
     * Coverage for one child type.
     *
     * @param observed request-thread entries of this type in the window, including those without a thread
     * @param nested entries among {@code observed} whose parent is a {@code REQUEST} entry of the window
     * @param withoutThread entries among {@code observed} that carry no thread name
     * @param otherThreads entries of this type in the window on threads that do not serve requests, which the
     *     measurement excludes
     * @param misattributed nested entries of this type, on any thread, whose request's interval does not contain them
     * @param unowned entries among {@code otherThreads} on the scenario's unowned threads, such as a raw executor the
     *     application did not wrap
     * @param unownedNested entries among {@code unowned} that are nested under a request of the window
     */
    public record TypeCoverage(
            String type,
            int observed,
            int nested,
            int withoutThread,
            int otherThreads,
            int misattributed,
            int unowned,
            int unownedNested) {

        public TypeCoverage(String type, int observed, int nested, int withoutThread, int otherThreads) {
            this(type, observed, nested, withoutThread, otherThreads, 0, 0, 0);
        }

        /** Nested share in {@code [0, 1]}, or {@code null} when nothing was observed. */
        public Double nestedShare() {
            return observed == 0 ? null : (double) nested / observed;
        }
    }

    /**
     * Coverage for one window.
     *
     * @param requests {@code REQUEST} entries in the window
     * @param requestsWithTraceId entries among {@code requests} that carry a trace id
     * @param requestsSharingAnId entries among {@code requests} whose id another request of the window also uses,
     *     which makes their profiles ambiguous
     * @param failedRequests entries among {@code requests} with a 5xx status
     * @param children per child type, in {@link #CHILD_TYPES} order
     */
    public record Report(
            int requests,
            int requestsWithTraceId,
            int requestsSharingAnId,
            int failedRequests,
            Map<String, TypeCoverage> children) {

        public Double requestTraceIdShare() {
            return requests == 0 ? null : (double) requestsWithTraceId / requests;
        }

        public TypeCoverage child(String type) {
            TypeCoverage coverage = children.get(type);
            return coverage != null ? coverage : new TypeCoverage(type, 0, 0, 0, 0);
        }

        /** A Markdown table, for the scenario's report file and test output. */
        public String toMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("| Measure | Observed | Linked | Share | Without thread | Other threads | Misattributed |\n");
            out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            out.append("| Requests carrying a trace id | ")
                    .append(requests)
                    .append(" | ")
                    .append(requestsWithTraceId)
                    .append(" | ")
                    .append(percent(requestTraceIdShare()))
                    .append(" | — | — | — |\n");
            for (TypeCoverage coverage : children.values()) {
                out.append("| ")
                        .append(coverage.type())
                        .append(" nested under its request | ")
                        .append(coverage.observed())
                        .append(" | ")
                        .append(coverage.nested())
                        .append(" | ")
                        .append(percent(coverage.nestedShare()))
                        .append(" | ")
                        .append(coverage.withoutThread())
                        .append(" | ")
                        .append(coverage.otherThreads())
                        .append(" | ")
                        .append(coverage.misattributed())
                        .append(" |\n");
            }
            for (TypeCoverage coverage : children.values()) {
                if (coverage.unowned() > 0) {
                    out.append("\n")
                            .append(coverage.type())
                            .append(" on unowned threads: ")
                            .append(coverage.unowned())
                            .append(", of which nested under a request: ")
                            .append(coverage.unownedNested())
                            .append('.');
                }
            }
            out.append("\nRequests sharing an id with another request: ")
                    .append(requestsSharingAnId)
                    .append(". Failed (5xx) requests: ")
                    .append(failedRequests)
                    .append(".\n");
            return out.toString();
        }

        private static String percent(Double share) {
            return share == null ? "—" : String.format(Locale.ROOT, "%.1f %%", share * 100);
        }
    }

    /**
     * Measures coverage over the entries whose timestamp falls in {@code [windowStart, windowEnd)}.
     *
     * @param requestThread the runtime's request-serving thread names, for example Tomcat's
     *     {@code http-nio-…-exec-N}
     */
    public static Report measure(List<Entry> entries, long windowStart, long windowEnd, Pattern requestThread) {
        return measure(entries, windowStart, windowEnd, windowEnd, requestThread, null);
    }

    /**
     * Measures coverage over the requests whose timestamp falls in {@code [windowStart, windowEnd)} and the children
     * whose timestamp falls in {@code [windowStart, childrenEnd)}, so that a child reported shortly after its request
     * returned, such as an acknowledged message send, still counts in its request's window.
     *
     * @param requestThread the runtime's request-serving thread names
     * @param unownedThread the scenario's unowned threads, such as a raw executor's, or {@code null} when it has none
     */
    public static Report measure(
            List<Entry> entries,
            long windowStart,
            long windowEnd,
            long childrenEnd,
            Pattern requestThread,
            Pattern unownedThread) {
        List<Entry> window = new ArrayList<>();
        List<Entry> childWindow = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.timestamp() >= windowStart && entry.timestamp() < windowEnd) {
                window.add(entry);
            }
            if (entry.timestamp() >= windowStart
                    && entry.timestamp() < Math.max(windowEnd, childrenEnd)
                    && !"REQUEST".equals(entry.type())
                    && !entry.consumedMessage()) {
                childWindow.add(entry);
            }
        }
        Map<String, Entry> requestsById = new HashMap<>();
        Map<String, Integer> requestIdUses = new HashMap<>();
        int requests = 0;
        int requestsWithTraceId = 0;
        int failedRequests = 0;
        for (Entry entry : window) {
            if (!"REQUEST".equals(entry.type())) {
                continue;
            }
            requests++;
            requestsById.put(entry.id(), entry);
            requestIdUses.merge(entry.id(), 1, Integer::sum);
            if (entry.correlationId() != null && !entry.correlationId().isBlank()) {
                requestsWithTraceId++;
            }
            if (entry.status() != null && entry.status() >= 500) {
                failedRequests++;
            }
        }
        int requestsSharingAnId = 0;
        for (int uses : requestIdUses.values()) {
            if (uses > 1) {
                requestsSharingAnId += uses;
            }
        }
        Map<String, TypeCoverage> children = new LinkedHashMap<>();
        for (String type : CHILD_TYPES) {
            int observed = 0;
            int nested = 0;
            int withoutThread = 0;
            int otherThreads = 0;
            int misattributed = 0;
            int unowned = 0;
            int unownedNested = 0;
            for (Entry entry : childWindow) {
                if (!type.equals(entry.type())) {
                    continue;
                }
                Entry parent = entry.parentId() == null ? null : requestsById.get(entry.parentId());
                if (parent != null && entry.hasThread() && outsideInterval(entry, parent)) {
                    misattributed++;
                }
                if (entry.hasThread() && !requestThread.matcher(entry.thread()).matches()) {
                    otherThreads++;
                    if (unownedThread != null
                            && unownedThread.matcher(entry.thread()).matches()) {
                        unowned++;
                        if (parent != null) {
                            unownedNested++;
                        }
                    }
                    continue;
                }
                observed++;
                if (!entry.hasThread()) {
                    withoutThread++;
                }
                if (parent != null) {
                    nested++;
                }
            }
            children.put(
                    type,
                    new TypeCoverage(
                            type,
                            observed,
                            nested,
                            withoutThread,
                            otherThreads,
                            misattributed,
                            unowned,
                            unownedNested));
        }
        return new Report(requests, requestsWithTraceId, requestsSharingAnId, failedRequests, children);
    }

    private static boolean outsideInterval(Entry child, Entry request) {
        if (request.durationMs() == null) {
            return false;
        }
        long start = request.timestamp() - INTERVAL_SLACK_MILLIS;
        long end = request.timestamp() + request.durationMs() + INTERVAL_SLACK_MILLIS;
        return child.timestamp() < start || child.timestamp() > end;
    }
}
