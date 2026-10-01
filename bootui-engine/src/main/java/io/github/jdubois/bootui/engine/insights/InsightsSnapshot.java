package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The projection observations read ({@code docs/PLAN-v2.md} §5.4): the journal's retained events grouped into completed
 * requests with their children, by route, and the coverage of each source. It is built once per read and never on the
 * capture path.
 */
public final class InsightsSnapshot {

    private final List<ProjectedRequest> requests;
    private final Map<String, List<ProjectedRequest>> byRoute;
    private final Map<JournalSource, long[]> coverage;
    private final JournalStatus status;
    private final Predicate<JournalSource> recorded;
    private final Predicate<JournalSource> visible;

    private InsightsSnapshot(
            List<ProjectedRequest> requests,
            Map<JournalSource, long[]> coverage,
            JournalStatus status,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible) {
        this.requests = Collections.unmodifiableList(requests);
        Map<String, List<ProjectedRequest>> routes = new LinkedHashMap<>();
        for (ProjectedRequest request : requests) {
            routes.computeIfAbsent(request.route(), route -> new ArrayList<>()).add(request);
        }
        this.byRoute = Collections.unmodifiableMap(routes);
        this.coverage = coverage;
        this.status = status;
        this.recorded = recorded;
        this.visible = visible;
    }

    /**
     * Projects {@code entries}, the journal's retained events in any order.
     *
     * @param recorded whether the journal records a source
     * @param visible whether a source's panel is enabled, so its events may be shown
     */
    public static InsightsSnapshot of(
            List<JournalEntry> entries,
            JournalStatus status,
            RouteTemplateResolver routes,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible) {
        List<JournalEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(JournalEntry::sequence));
        Map<String, RuntimeEvent> http = new HashMap<>();
        Map<String, List<RuntimeEvent>> children = new HashMap<>();
        Map<JournalSource, long[]> coverage = new EnumMap<>(JournalSource.class);
        for (JournalEntry entry : ordered) {
            RuntimeEvent event = entry.event();
            long[] counts = coverage.computeIfAbsent(event.source(), source -> new long[3]);
            if (event.requestId() != null) {
                counts[0]++;
            } else if (event.executionId() != null) {
                counts[1]++;
            } else {
                counts[2]++;
            }
            if (event.requestId() == null) {
                continue;
            }
            if (event.source() == JournalSource.HTTP) {
                http.put(event.requestId(), event);
            } else {
                children.computeIfAbsent(event.requestId(), id -> new ArrayList<>())
                        .add(event);
            }
        }
        List<ProjectedRequest> requests = new ArrayList<>(http.size());
        for (Map.Entry<String, RuntimeEvent> request : http.entrySet()) {
            RuntimeEvent event = request.getValue();
            if (!(event.payload() instanceof HttpPayload payload)) {
                continue;
            }
            RouteLabel label = RouteLabel.of(
                    payload.method(), payload.path(), payload.routeTemplate(), payload.operation(), routes);
            requests.add(new ProjectedRequest(
                    request.getKey(),
                    label.id(),
                    payload.method(),
                    payload.path(),
                    payload.status(),
                    event.epochMillis(),
                    Math.max(0, event.durationNanos()),
                    children.getOrDefault(request.getKey(), List.of())));
        }
        requests.sort(
                Comparator.comparingLong(ProjectedRequest::startMillis).thenComparing(ProjectedRequest::requestId));
        return new InsightsSnapshot(requests, coverage, status, recorded, visible);
    }

    /** Every completed request retained, oldest first. */
    public List<ProjectedRequest> requests() {
        return requests;
    }

    /** The completed requests per route label, routes in first-seen order. */
    public Map<String, List<ProjectedRequest>> byRoute() {
        return byRoute;
    }

    /** The journal's status when the snapshot was taken. */
    public JournalStatus status() {
        return status;
    }

    /** Whether the journal records {@code source}. */
    public boolean records(JournalSource source) {
        return recorded.test(source);
    }

    /** Whether {@code source}'s panel is enabled, so observations may show its evidence. */
    public boolean visible(JournalSource source) {
        return visible.test(source);
    }

    /** Events the journal dropped from {@code source} in this run. */
    public long dropped(JournalSource source) {
        return status.dropped().getOrDefault(source, 0L);
    }

    /** The retained events of each source: linked to a request, to an execution only, and to neither. */
    public Map<JournalSource, long[]> coverage() {
        return Collections.unmodifiableMap(coverage);
    }
}
