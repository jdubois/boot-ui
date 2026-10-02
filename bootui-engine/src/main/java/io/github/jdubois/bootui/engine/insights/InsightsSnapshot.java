package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AiCallOwners;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RunSummary;
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
import java.util.Optional;
import java.util.function.Function;
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
    private final InsightsStack stack;
    private final RunSummary previousRun;
    private final Function<String, Integer> poolSizes;

    private InsightsSnapshot(
            List<ProjectedRequest> requests,
            Map<JournalSource, long[]> coverage,
            JournalStatus status,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible,
            InsightsStack stack,
            RunSummary previousRun,
            Function<String, Integer> poolSizes) {
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
        this.stack = stack;
        this.previousRun = previousRun;
        this.poolSizes = poolSizes;
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
        return of(entries, status, routes, recorded, visible, null, null);
    }

    /**
     * Projects {@code entries} for {@code stack}, with the summary of the run before this one.
     *
     * @param stack the stack serving the application, or {@code null} when unknown
     * @param previousRun the previous run's summary, or {@code null} when none is kept
     */
    public static InsightsSnapshot of(
            List<JournalEntry> entries,
            JournalStatus status,
            RouteTemplateResolver routes,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible,
            InsightsStack stack,
            RunSummary previousRun) {
        return of(entries, status, routes, recorded, visible, stack, previousRun, null);
    }

    /**
     * Projects {@code entries} for {@code stack}, with the previous run's summary and the size of each connection pool.
     *
     * @param poolSizes the maximum size of a data source's connection pool, by the data source name its connections
     *     carry, or {@code null} when unknown
     */
    public static InsightsSnapshot of(
            List<JournalEntry> entries,
            JournalStatus status,
            RouteTemplateResolver routes,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible,
            InsightsStack stack,
            RunSummary previousRun,
            Function<String, Integer> poolSizes) {
        return of(entries, status, routes, recorded, visible, stack, previousRun, poolSizes, traceId -> false);
    }

    /**
     * Projects {@code entries} as {@link #of(List, JournalStatus, RouteTemplateResolver, Predicate, Predicate,
     * InsightsStack, RunSummary, Function)} does, with {@code evictedRequestTraces} naming the traces of requests the
     * journal evicted, whose AI calls linked only by trace belong to no request ({@link AiCallOwners}).
     */
    public static InsightsSnapshot of(
            List<JournalEntry> entries,
            JournalStatus status,
            RouteTemplateResolver routes,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible,
            InsightsStack stack,
            RunSummary previousRun,
            Function<String, Integer> poolSizes,
            Predicate<String> evictedRequestTraces) {
        List<JournalEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(JournalEntry::sequence));
        Map<String, RuntimeEvent> http = new HashMap<>();
        Map<String, List<RuntimeEvent>> children = new HashMap<>();
        Map<JournalSource, long[]> coverage = new EnumMap<>(JournalSource.class);
        List<RuntimeEvent> traced = new ArrayList<>();
        AiCallOwners aiCallOwners = new AiCallOwners(evictedRequestTraces);
        for (JournalEntry entry : ordered) {
            RuntimeEvent event = entry.event();
            aiCallOwners.learn(event);
            long[] counts = coverage.computeIfAbsent(event.source(), source -> new long[4]);
            if (event.requestId() != null) {
                counts[0]++;
            } else if (event.executionId() != null) {
                counts[1]++;
            } else if (AiCallOwners.linksByTrace(event)) {
                // An AI span carries only its trace id, so it is linked to its request by its trace and time below.
                traced.add(event);
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
        // Attributed as the feed and request profiles attribute it (AiCallOwners).
        traced.sort(Comparator.comparingLong(RuntimeEvent::epochMillis));
        for (RuntimeEvent event : traced) {
            String requestId = aiCallOwners.ownerOf(event);
            long[] counts = coverage.get(event.source());
            if (requestId == null) {
                counts[2]++;
            } else {
                counts[3]++;
                children.computeIfAbsent(requestId, id -> new ArrayList<>()).add(event);
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
                    children.getOrDefault(request.getKey(), List.of()),
                    payload.timing(),
                    payload.resources(),
                    event.traceId()));
        }
        requests.sort(
                Comparator.comparingLong(ProjectedRequest::startMillis).thenComparing(ProjectedRequest::requestId));
        return new InsightsSnapshot(requests, coverage, status, recorded, visible, stack, previousRun, poolSizes);
    }

    /** Every completed request retained, oldest first. */
    public List<ProjectedRequest> requests() {
        return requests;
    }

    /** The completed requests per route label, routes in first-seen order. */
    public Map<String, List<ProjectedRequest>> byRoute() {
        return byRoute;
    }

    /** The stack serving the application, or {@code null} when unknown. */
    public InsightsStack stack() {
        return stack;
    }

    /** The maximum size of {@code dataSource}'s connection pool, when known. */
    public Optional<Integer> poolSize(String dataSource) {
        if (poolSizes == null) {
            return Optional.empty();
        }
        try {
            Integer size = poolSizes.apply(dataSource);
            return size == null || size <= 0 ? Optional.empty() : Optional.of(size);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** The summary of the run before this one in the same JVM, when one is kept. */
    public Optional<RunSummary> previousRun() {
        return Optional.ofNullable(previousRun);
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

    /**
     * The retained events of each source: linked to a request by its id, to an execution only, to neither, and to a
     * request by its trace id.
     */
    public Map<JournalSource, long[]> coverage() {
        return Collections.unmodifiableMap(coverage);
    }
}
