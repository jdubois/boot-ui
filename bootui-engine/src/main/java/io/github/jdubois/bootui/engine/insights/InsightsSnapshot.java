package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AiCallOwners;
import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.LifecyclePayload;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
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
    private final List<RuntimeEvent> collections;
    private final List<RuntimeEvent> markers;
    private final Map<String, List<ProjectedRequest>> byRoute;
    private final List<ProjectedRequest> httpRequests;
    private final Map<String, List<ProjectedRequest>> httpByRoute;
    private final Map<JournalSource, long[]> coverage;
    private final JournalStatus status;
    private final Predicate<JournalSource> recorded;
    private final Predicate<JournalSource> visible;
    private final InsightsStack stack;
    private final RunSummary previousRun;
    private final Function<String, Integer> poolSizes;
    private JournalTextExposure exposure = JournalTextExposure.masked();

    private InsightsSnapshot(
            List<ProjectedRequest> requests,
            List<RuntimeEvent> collections,
            List<RuntimeEvent> markers,
            Map<JournalSource, long[]> coverage,
            JournalStatus status,
            Predicate<JournalSource> recorded,
            Predicate<JournalSource> visible,
            InsightsStack stack,
            RunSummary previousRun,
            Function<String, Integer> poolSizes) {
        this.requests = Collections.unmodifiableList(requests);
        this.collections = Collections.unmodifiableList(collections);
        this.markers = Collections.unmodifiableList(markers);
        Map<String, List<ProjectedRequest>> routes = new LinkedHashMap<>();
        for (ProjectedRequest request : requests) {
            routes.computeIfAbsent(request.route(), route -> new ArrayList<>()).add(request);
        }
        this.byRoute = Collections.unmodifiableMap(routes);
        List<ProjectedRequest> http = new ArrayList<>();
        Map<String, List<ProjectedRequest>> httpRoutes = new LinkedHashMap<>();
        for (ProjectedRequest request : requests) {
            if (request.http()) {
                http.add(request);
                httpRoutes
                        .computeIfAbsent(request.route(), route -> new ArrayList<>())
                        .add(request);
            }
        }
        this.httpRequests = Collections.unmodifiableList(http);
        this.httpByRoute = Collections.unmodifiableMap(httpRoutes);
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
        Map<String, RuntimeEvent> executions = new HashMap<>();
        Map<String, List<RuntimeEvent>> executionChildren = new HashMap<>();
        Map<JournalSource, long[]> coverage = new EnumMap<>(JournalSource.class);
        List<RuntimeEvent> traced = new ArrayList<>();
        List<RuntimeEvent> collections = new ArrayList<>();
        List<RuntimeEvent> markers = new ArrayList<>();
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
            if (event.requestId() == null && event.executionId() != null) {
                if (opensExecution(event)) {
                    executions.put(event.executionId(), event);
                } else {
                    executionChildren
                            .computeIfAbsent(event.executionId(), id -> new ArrayList<>())
                            .add(event);
                }
                continue;
            }
            if (event.requestId() == null) {
                if (event.payload() instanceof GcPayload) {
                    collections.add(event);
                } else if (event.payload() instanceof LifecyclePayload lifecycle && lifecycle.marker()) {
                    markers.add(event);
                }
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
                    event.traceId(),
                    event.thread(),
                    ProjectedRequest.Kind.HTTP));
        }
        for (Map.Entry<String, RuntimeEvent> execution : executions.entrySet()) {
            RuntimeEvent event = execution.getValue();
            String name = executionName(event);
            if (name == null) {
                continue;
            }
            requests.add(new ProjectedRequest(
                    execution.getKey(),
                    name,
                    null,
                    null,
                    0,
                    event.epochMillis(),
                    Math.max(0, event.durationNanos()),
                    executionChildren.getOrDefault(execution.getKey(), List.of()),
                    null,
                    null,
                    event.traceId(),
                    event.thread(),
                    event.payload() instanceof ScheduledPayload
                            ? ProjectedRequest.Kind.SCHEDULED
                            : ProjectedRequest.Kind.MESSAGE));
        }
        requests.sort(
                Comparator.comparingLong(ProjectedRequest::startMillis).thenComparing(ProjectedRequest::requestId));
        return new InsightsSnapshot(
                requests, collections, markers, coverage, status, recorded, visible, stack, previousRun, poolSizes);
    }

    /** Every garbage collection retained, in the order the journal recorded them, each with a {@link GcPayload}. */
    public List<RuntimeEvent> collections() {
        return collections;
    }

    /**
     * The control and availability markers retained, in the order the journal recorded them, each with a {@link
     * LifecyclePayload} (M4-7).
     */
    public List<RuntimeEvent> markers() {
        return markers;
    }

    /** Every completed request, scheduled run, and consumed message retained, oldest first. */
    public List<ProjectedRequest> requests() {
        return requests;
    }

    /**
     * The completed requests, scheduled runs, and consumed messages per route label or execution name, in first-seen
     * order, for the observations that read a unit of work's own children.
     */
    public Map<String, List<ProjectedRequest>> byRoute() {
        return byRoute;
    }

    /** Every completed HTTP request retained, oldest first. */
    public List<ProjectedRequest> httpRequests() {
        return httpRequests;
    }

    /**
     * The completed HTTP requests per route label, for the observations that read what only a request has: its status,
     * method, phases, authorization, or measured resources.
     */
    public Map<String, List<ProjectedRequest>> httpByRoute() {
        return httpByRoute;
    }

    /** Whether {@code event} opens an execution: a scheduled run or a consumed message, as the feed renders it. */
    static boolean opensExecution(RuntimeEvent event) {
        return event.payload() instanceof ScheduledPayload
                || (event.payload() instanceof MessagingPayload message && !message.sent())
                || (event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution());
    }

    /** An execution's name, like a route's: {@code @Scheduled OrderJob.run} or {@code consume kafka:orders}. */
    static String executionName(RuntimeEvent event) {
        if (event.payload() instanceof ScheduledPayload job) {
            return job.task() == null ? null : "@Scheduled " + job.task();
        }
        if (event.payload() instanceof MessagingPayload message && message.destination() != null) {
            return "consume " + (message.broker() == null ? "" : message.broker() + ":") + message.destination();
        }
        if (event.payload() instanceof WebSocketPayload webSocket && webSocket.destination() != null) {
            // A WebSocket message handler runs like a listener, so it reads as one (M4-10).
            return "consume websocket:" + webSocket.destination();
        }
        return null;
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

    /**
     * Whether {@code source}'s evidence is in this projection: the journal records it and the panel that owns it is
     * enabled ({@code docs/PLAN-v2.md} §8). An observation that treats a source as optional evidence reads this rather
     * than {@link #records(JournalSource)}, so a disabled panel reads as absent evidence instead of as proof that
     * nothing happened.
     */
    public boolean available(JournalSource source) {
        return records(source) && visible(source);
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

    /**
     * Sets the exposure rule the projection quotes recorded text under ({@code PLAN-v2} §8), before any observation reads
     * this snapshot. The snapshot is confined to the projection that built it.
     */
    InsightsSnapshot withExposure(JournalTextExposure exposure) {
        this.exposure = exposure == null ? JournalTextExposure.masked() : exposure;
        return this;
    }

    /** The exposure rule recorded text is quoted under: log messages and request paths. */
    public JournalTextExposure exposure() {
        return exposure;
    }
}
