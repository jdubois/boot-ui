package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import io.github.jdubois.bootui.engine.model.ObservedEdges;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceTrack;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The incremental aggregates observations read ({@code docs/PLAN-v2.md} §5.2), maintained by the journal's dispatcher
 * from every accepted event, before any eviction: per route, per statement fingerprint, per exception group and route,
 * per transactional method, per thread family without a request, and per run. Each dimension has a cardinality cap and
 * a visible {@code Other} bucket.
 *
 * <p>A request's children usually complete before the request does, so the aggregates hold each open request's child
 * time, statement fingerprints, and exception groups until its HTTP event arrives, and then fold them into its route.
 * At most {@value #MAX_PENDING_REQUESTS} requests are held; beyond that, the oldest is dropped and counted as
 * unattributed, as is a request whose HTTP event never arrives.</p>
 *
 * <p>A request's GC pauses join the {@code GC} events by collector and id ({@code docs/PLAN-v2.md} §5.11). A collection
 * whose notification arrives after the request was folded waits, up to {@value #MAX_AWAITED_COLLECTIONS} of them, and
 * adds its pause to the request's route when it arrives.</p>
 *
 * <p>They also count the runtime model's observed edges ({@code docs/PLAN-v2.md} §5.8), named by
 * {@link ObservedEdges} as the model's projection names them, so the run summary keeps the whole run's edge set, and an
 * edge whose events the journal later evicts still counts. An execution's events usually arrive before it completes, so
 * their edges wait, up to {@value #MAX_EDGES_PER_EXECUTION} per execution, until the execution names its node; at most
 * {@value #MAX_PENDING_REQUESTS} executions wait, and an AI span exported after its request joins it by trace id.
 * Beyond {@value #MAX_EDGES} edges, an observation of a new edge is counted under {@value #EDGES} in
 * {@link AggregatesSnapshot#overflowed()}.</p>
 *
 * <p>The dispatcher updates the aggregates under their monitor, and readers take an immutable {@link #snapshot()}.</p>
 */
public final class JournalAggregates implements JournalListener {

    public static final int MAX_ROUTES = 500;
    public static final int MAX_FINGERPRINTS = 2_000;
    public static final int MAX_FINGERPRINTS_PER_ROUTE = 50;
    public static final int MAX_EXCEPTION_GROUPS = 500;
    public static final int MAX_ROUTES_PER_EXCEPTION_GROUP = 50;
    public static final int MAX_TRANSACTIONAL_METHODS = 500;
    public static final int MAX_THREAD_FAMILIES = 100;
    public static final int MAX_CALL_SITES = 20;
    public static final int MAX_PENDING_REQUESTS = 4_096;
    public static final int MAX_EDGES = 5_000;

    /** The {@link AggregatesSnapshot#overflowed()} key of the edge observations not counted beyond {@value #MAX_EDGES}. */
    public static final String EDGES = "edges";

    static final int MAX_EDGES_PER_EXECUTION = 64;
    static final int MAX_FINGERPRINTS_PER_REQUEST = 64;
    static final int MAX_GROUPS_PER_REQUEST = 16;
    static final int MAX_AWAITED_COLLECTIONS = 4_096;
    static final int MAX_RECENT_COLLECTIONS = 4_096;
    static final int MAX_JOINED_COLLECTIONS_PER_REQUEST = 64;

    private static final int SOURCES = JournalSource.values().length;

    private static final Logger log = Logger.getLogger(JournalAggregates.class.getName());

    private final CappedMap<Route> routes = new CappedMap<>(MAX_ROUTES, Route::new);
    private final CappedMap<Statement> statements = new CappedMap<>(MAX_FINGERPRINTS, Statement::new);
    private final CappedMap<ExceptionGroup> exceptionGroups =
            new CappedMap<>(MAX_EXCEPTION_GROUPS, ExceptionGroup::new);
    private final CappedMap<TransactionalMethod> transactionalMethods =
            new CappedMap<>(MAX_TRANSACTIONAL_METHODS, TransactionalMethod::new);
    private final CappedMap<ThreadFamily> threadFamilies = new CappedMap<>(MAX_THREAD_FAMILIES, ThreadFamily::new);
    private final LinkedHashMap<String, PendingRequest> pending = new LinkedHashMap<>();

    /**
     * The most recently completed requests, so a child recorded after its request, such as an AI call exported in a
     * later batch or a send acknowledged after the response, opens no pending entry that nothing would ever complete.
     */
    private final Map<String, Boolean> completed = bounded(MAX_PENDING_REQUESTS);

    private final Map<String, Long> recentPauses = bounded(MAX_RECENT_COLLECTIONS);
    private final Map<String, List<String>> awaitedPauses = bounded(MAX_AWAITED_COLLECTIONS);
    private final ResourceTrack resourceTrack = new ResourceTrack();
    private final LinkedHashMap<EdgeRef, EdgeCount> edges = new LinkedHashMap<>();
    private final LinkedHashMap<String, PendingEdges> pendingEdges = new LinkedHashMap<>();
    private final Map<String, ObservedEdges.Execution> executions = bounded(MAX_PENDING_REQUESTS);
    private final Map<String, ObservedEdges.Execution> requestsByTrace = bounded(MAX_PENDING_REQUESTS);
    private long edgeOverflow;
    private final long[] runCounts = new long[SOURCES];
    private final long[] runNanos = new long[SOURCES];
    private long firstEpochMillis = Long.MAX_VALUE;
    private long lastEpochMillis = Long.MIN_VALUE;
    private long failedRequests;
    private long unattributedRequests;
    private volatile Supplier<RouteTemplateResolver> declaredRoutes = RouteTemplateResolver::empty;
    private volatile RunHistory history;
    private volatile RunIdentity run;
    private volatile RunBaselineFile baseline;
    private RunStart runStart;

    /**
     * Installs the application's declared routes, which name a request's route when the framework recorded no
     * template, as on Quarkus.
     */
    public void setDeclaredRoutes(Supplier<RouteTemplateResolver> declaredRoutes) {
        this.declaredRoutes = declaredRoutes == null ? RouteTemplateResolver::empty : declaredRoutes;
    }

    /** The application's declared routes, as installed now, for views that name routes as the aggregates do. */
    public Supplier<RouteTemplateResolver> declaredRoutes() {
        return () -> declaredRoutes.get();
    }

    /**
     * Keeps the summary of {@code run} in {@code history} when the journal closes at the end of the run, so a later
     * run can be compared with it ({@code docs/PLAN-v2.md} §5.2, §5.8).
     */
    public void recordRunIn(RunHistory history, RunIdentity run) {
        recordRunIn(history, run, null);
    }

    /**
     * Keeps the summary of {@code run} in {@code history} when the journal closes, and writes it to {@code baseline}
     * when it is set, after reading that file into {@code history} as the previous run if {@code history} keeps none
     * ({@code docs/PLAN-v2.md} §5.8).
     */
    public void recordRunIn(RunHistory history, RunIdentity run, RunBaselineFile baseline) {
        this.history = history;
        this.run = run;
        this.baseline = baseline;
        if (history != null) {
            history.loadBaseline(baseline);
        }
    }

    @Override
    public void onClose() {
        RunHistory target = history;
        RunIdentity ended = run;
        if (target != null && ended != null) {
            RunSummary summary = RunSummary.of(ended, snapshot(), runStart(), System.currentTimeMillis());
            target.record(summary);
            RunBaselineFile file = baseline;
            if (file != null) {
                try {
                    file.write(summary);
                } catch (IOException | RuntimeException ex) {
                    log.log(Level.WARNING, "BootUI could not write the baseline file " + file.path(), ex);
                }
            }
        }
    }

    @Override
    public synchronized void onEntries(List<JournalEntry> entries) {
        for (JournalEntry entry : entries) {
            accept(entry.event());
        }
    }

    private void accept(RuntimeEvent event) {
        int source = event.source().ordinal();
        runCounts[source]++;
        runNanos[source] += Math.max(0, event.durationNanos());
        firstEpochMillis = Math.min(firstEpochMillis, event.epochMillis());
        lastEpochMillis = Math.max(lastEpochMillis, event.epochMillis());
        RuntimeEventPayload payload = event.payload();
        if (payload instanceof GcPayload gc) {
            collected(gc, Math.max(0, event.durationNanos()));
            return;
        }
        if (payload instanceof LifecyclePayload lifecycle) {
            if (runStart == null && LifecyclePayload.RUN_STARTED.equals(lifecycle.kind())) {
                runStart = lifecycle.runStart();
            }
            return;
        }
        String label =
                event.source() == JournalSource.HTTP && payload instanceof HttpPayload http ? routeOf(http) : null;
        observeEdges(event, label);
        // An event not observed on a thread, such as an AI call joined to its request by trace id or a message sent
        // from an I/O callback, belongs to no thread family rather than inflating an unknown one.
        if (event.requestId() == null && event.executionId() == null && event.thread() != null) {
            threadFamilies.get(ThreadFamilies.of(event.thread())).add(event);
        }
        if (event.source() == JournalSource.HTTP && payload instanceof HttpPayload http) {
            if (http.status() >= 500) {
                failedRequests++;
            }
            Route route = routes.get(label);
            route.add(event, http.status());
            if (http.resources() != null) {
                route.resources(http.resources());
                joinPauses(http.resources(), label, route);
            }
            PendingRequest children = event.requestId() == null ? null : pending.remove(event.requestId());
            if (children != null) {
                route.fold(children, label, this);
            }
            if (event.requestId() != null) {
                completed.put(event.requestId(), Boolean.TRUE);
            }
            return;
        }
        PendingRequest children = event.requestId() == null || completed.containsKey(event.requestId())
                ? null
                : pendingFor(event.requestId());
        if (children != null) {
            children.add(event);
        }
        if (payload instanceof SqlPayload sql) {
            String fingerprint = SqlShapes.fingerprint(sql.sql());
            statements.get(fingerprint).add(event, sql);
            if (children != null) {
                children.statement(fingerprint);
            }
        } else if (payload instanceof ExceptionPayload exception) {
            exceptionGroups.get(exception.groupId()).add(exception);
            if (children != null) {
                children.exceptionGroup(exception.groupId());
            }
        } else if (payload instanceof TransactionPayload transaction) {
            transactionalMethods.get(transaction.method()).add(event, transaction);
        } else if (payload instanceof ConnectionPayload connection && children != null) {
            children.connectionWaitNanos += connection.waitNanos();
        } else if (payload instanceof CachePayload cache
                && children != null
                && "MISS".equalsIgnoreCase(cache.operation())) {
            children.cacheMisses++;
        } else if (payload instanceof AiPayload ai && children != null) {
            children.aiTokens += Math.max(0, ai.inputTokens() == null ? 0 : ai.inputTokens())
                    + Math.max(0, ai.outputTokens() == null ? 0 : ai.outputTokens());
        }
    }

    /**
     * Counts the edges {@code event} adds from the execution that owns it, or holds them until that execution
     * completes, and, when {@code event} completes an execution, counts the edges its earlier events held.
     */
    private void observeEdges(RuntimeEvent event, String routeLabel) {
        ObservedEdges.Execution execution = ObservedEdges.execution(event, http -> routeLabel);
        if (execution != null) {
            String key = ObservedEdges.executionKey(event, execution);
            executions.put(key, execution);
            if (execution.request() && event.traceId() != null) {
                requestsByTrace.put(event.traceId(), execution);
            }
            PendingEdges held = pendingEdges.remove(key);
            if (held != null) {
                held.targets.forEach((target, count) -> count(execution.to(target), count));
            }
        }
        List<ObservedEdges.Target> targets = ObservedEdges.targets(event);
        if (targets.isEmpty()) {
            return;
        }
        String owner = ObservedEdges.ownerKey(event);
        ObservedEdges.Execution completed = owner != null
                ? executions.get(owner)
                : ObservedEdges.ownedByTrace(event) ? requestsByTrace.get(event.traceId()) : null;
        long at = event.epochMillis();
        if (completed != null) {
            for (ObservedEdges.Target target : targets) {
                EdgeCount observed = new EdgeCount(at);
                count(completed.to(target), observed);
            }
        } else if (owner != null) {
            PendingEdges held = pendingEdges.get(owner);
            if (held == null) {
                if (pendingEdges.size() >= MAX_PENDING_REQUESTS) {
                    Iterator<String> oldest = pendingEdges.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                held = new PendingEdges();
                pendingEdges.put(owner, held);
            }
            for (ObservedEdges.Target target : targets) {
                held.add(target, at);
            }
        }
    }

    private void count(EdgeRef edge, EdgeCount observed) {
        EdgeCount count = edges.get(edge);
        if (count != null) {
            count.merge(observed);
        } else if (edges.size() < MAX_EDGES) {
            edges.put(edge, observed.copy());
        } else {
            edgeOverflow += observed.count;
        }
    }

    /** Adds a collection's pause to the routes of the requests already folded that it completed during. */
    private void collected(GcPayload gc, long pauseNanos) {
        if (!gc.pause() || gc.collector() == null) {
            return;
        }
        String key = collectionKey(gc.collector(), gc.gcId());
        recentPauses.put(key, pauseNanos);
        List<String> labels = awaitedPauses.remove(key);
        if (labels != null) {
            for (String label : labels) {
                routes.get(label).gcPauseNanos += pauseNanos;
            }
        }
    }

    /**
     * Adds the pauses of the collections a request's segments saw complete to its route: now for the collections whose
     * events already arrived, and when they arrive for the others.
     */
    private void joinPauses(ResourceUsage usage, String label, Route route) {
        int joined = 0;
        for (GcPauseRange range : usage.gcPauseRanges()) {
            for (long id = range.afterId() + 1; id <= range.lastId(); id++) {
                if (joined++ >= MAX_JOINED_COLLECTIONS_PER_REQUEST) {
                    return;
                }
                String key = collectionKey(range.collector(), id);
                Long pauseNanos = recentPauses.get(key);
                if (pauseNanos != null) {
                    route.gcPauseNanos += pauseNanos;
                } else {
                    awaitedPauses
                            .computeIfAbsent(key, ignored -> new ArrayList<>(1))
                            .add(label);
                }
            }
        }
    }

    private static String collectionKey(String collector, long id) {
        return collector + '#' + id;
    }

    /** A map that keeps its {@code max} most recently inserted entries. */
    private static <V> Map<String, V> bounded(int max) {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    /** The route label of a request, such as {@code GET /api/orders/{id}}, as HTTP route rankings name it. */
    private String routeOf(HttpPayload http) {
        RouteTemplateResolver declared;
        try {
            declared = declaredRoutes.get();
        } catch (RuntimeException ex) {
            declared = RouteTemplateResolver.empty();
        }
        return RouteLabel.of(http.method(), http.path(), http.routeTemplate(), http.operation(), declared)
                .id();
    }

    private PendingRequest pendingFor(String requestId) {
        if (requestId == null) {
            return null;
        }
        PendingRequest children = pending.get(requestId);
        if (children == null) {
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                Iterator<String> oldest = pending.keySet().iterator();
                oldest.next();
                oldest.remove();
                unattributedRequests++;
            }
            children = new PendingRequest();
            pending.put(requestId, children);
        }
        return children;
    }

    /**
     * What the run recorded when it started, from its first {@link LifecyclePayload#RUN_STARTED} event, or {@code null}
     * before it. <b>Clear recording</b> keeps it, as it describes the run rather than its activity.
     */
    public synchronized RunStart runStart() {
        return runStart;
    }

    /** The run's resource track and CPU ledger (§5.11), which the resource sampler fills. */
    public ResourceTrack resourceTrack() {
        return resourceTrack;
    }

    /**
     * Drops every aggregate when the journal clears its recording, in step with it, so no batch processed before the
     * clear is counted after it.
     */
    @Override
    public void onClear() {
        clear();
    }

    /** Drops every aggregate, for <b>Clear recording</b>. */
    public synchronized void clear() {
        resourceTrack.clear();
        routes.clear();
        statements.clear();
        exceptionGroups.clear();
        transactionalMethods.clear();
        threadFamilies.clear();
        pending.clear();
        completed.clear();
        recentPauses.clear();
        awaitedPauses.clear();
        edges.clear();
        pendingEdges.clear();
        executions.clear();
        requestsByTrace.clear();
        edgeOverflow = 0;
        Arrays.fill(runCounts, 0);
        Arrays.fill(runNanos, 0);
        firstEpochMillis = Long.MAX_VALUE;
        lastEpochMillis = Long.MIN_VALUE;
        failedRequests = 0;
        unattributedRequests = 0;
    }

    /**
     * The route labels of the requests this run completed, such as {@code GET /api/orders/{id}}, without copying their
     * statistics, and whether more routes were seen than {@value #MAX_ROUTES} kept.
     */
    public synchronized RouteLabels routeLabels() {
        return new RouteLabels(Set.copyOf(routes.entries().keySet()), routes.overflowed() > 0);
    }

    /**
     * The route labels of a run.
     *
     * @param labels the labels kept
     * @param overflowed whether some routes were not kept
     */
    public record RouteLabels(Set<String> labels, boolean overflowed) {}

    /** An immutable copy of every aggregate. */
    public synchronized AggregatesSnapshot snapshot() {
        List<RouteStats> routeStats = new ArrayList<>();
        routes.entries().forEach((key, route) -> routeStats.add(route.stats(key)));
        List<StatementStats> statementStats = new ArrayList<>();
        statements.entries().forEach((key, statement) -> statementStats.add(statement.stats(key)));
        List<ExceptionGroupStats> groupStats = new ArrayList<>();
        exceptionGroups.entries().forEach((key, group) -> groupStats.add(group.stats(key)));
        List<TransactionalMethodStats> methodStats = new ArrayList<>();
        transactionalMethods.entries().forEach((key, method) -> methodStats.add(method.stats(key)));
        List<ThreadFamilyStats> familyStats = new ArrayList<>();
        threadFamilies.entries().forEach((key, family) -> familyStats.add(family.stats(key)));
        RunStats run = new RunStats(
                bySource(runCounts),
                bySource(runNanos),
                firstEpochMillis == Long.MAX_VALUE ? null : firstEpochMillis,
                lastEpochMillis == Long.MIN_VALUE ? null : lastEpochMillis,
                runCounts[JournalSource.HTTP.ordinal()],
                failedRequests,
                unattributedRequests,
                pending.size());
        Map<String, Long> overflowed = new LinkedHashMap<>();
        overflowed.put("routes", routes.overflowed());
        overflowed.put("statements", statements.overflowed());
        overflowed.put("exceptionGroups", exceptionGroups.overflowed());
        overflowed.put("transactionalMethods", transactionalMethods.overflowed());
        overflowed.put("threadFamilies", threadFamilies.overflowed());
        overflowed.put(EDGES, edgeOverflow);
        List<ObservedEdge> edgeStats = new ArrayList<>(edges.size());
        edges.forEach((edge, count) -> edgeStats.add(new ObservedEdge(edge, count.count, count.first, count.last)));
        return new AggregatesSnapshot(
                routeStats, statementStats, groupStats, methodStats, familyStats, edgeStats, run, overflowed);
    }

    private static Map<JournalSource, Long> bySource(long[] values) {
        Map<JournalSource, Long> map = new EnumMap<>(JournalSource.class);
        for (JournalSource source : JournalSource.values()) {
            if (values[source.ordinal()] != 0) {
                map.put(source, values[source.ordinal()]);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** An edge's observations: how many, and the first and last. */
    private static final class EdgeCount {

        private long count;
        private long first;
        private long last;

        EdgeCount(long at) {
            this(1, at, at);
        }

        private EdgeCount(long count, long first, long last) {
            this.count = count;
            this.first = first;
            this.last = last;
        }

        void merge(EdgeCount other) {
            count += other.count;
            first = Math.min(first, other.first);
            last = Math.max(last, other.last);
        }

        EdgeCount copy() {
            return new EdgeCount(count, first, last);
        }
    }

    /** The edges of an execution's events that arrived before it completed. */
    private final class PendingEdges {

        private final Map<ObservedEdges.Target, EdgeCount> targets = new LinkedHashMap<>();

        void add(ObservedEdges.Target target, long at) {
            EdgeCount count = targets.get(target);
            if (count != null) {
                count.merge(new EdgeCount(at));
            } else if (targets.size() < MAX_EDGES_PER_EXECUTION) {
                targets.put(target, new EdgeCount(at));
            } else {
                edgeOverflow++;
            }
        }
    }

    private static final class PendingRequest {

        private final long[] counts = new long[SOURCES];
        private final long[] nanos = new long[SOURCES];
        private final Map<String, Long> statements = new LinkedHashMap<>();
        private final Set<String> exceptionGroups = new LinkedHashSet<>();
        private long connectionWaitNanos;
        private long cacheMisses;
        private long aiTokens;

        void add(RuntimeEvent event) {
            counts[event.source().ordinal()]++;
            nanos[event.source().ordinal()] += Math.max(0, event.durationNanos());
        }

        void statement(String fingerprint) {
            String key = statements.containsKey(fingerprint) || statements.size() < MAX_FINGERPRINTS_PER_REQUEST
                    ? fingerprint
                    : CappedMap.OTHER;
            statements.merge(key, 1L, Long::sum);
        }

        void exceptionGroup(String groupId) {
            if (exceptionGroups.size() < MAX_GROUPS_PER_REQUEST) {
                exceptionGroups.add(groupId);
            }
        }
    }

    private static final class Route {

        private final LatencyHistogram latency = new LatencyHistogram();
        private final LatencyHistogram warmLatency = new LatencyHistogram();
        private final long[] statusClasses = new long[5];
        private final long[] childCounts = new long[SOURCES];
        private final long[] childNanos = new long[SOURCES];
        private final CappedMap<long[]> statements = new CappedMap<>(MAX_FINGERPRINTS_PER_ROUTE, () -> new long[1]);
        private long connectionWaitNanos;
        private long measuredRequests;
        private long partialRequests;
        private long unmeasuredRequests;
        private long cpuNanos;
        private long allocatedBytes;
        private long gcPauses;
        private long requestsWithGcPause;
        private long gcPauseNanos;
        private long cacheMisses;
        private long aiTokens;

        void resources(ResourceUsage usage) {
            switch (usage.availability()) {
                case AVAILABLE -> {
                    measuredRequests++;
                    cpuNanos += usage.cpuNanos();
                    allocatedBytes += usage.allocatedBytes();
                }
                case PARTIAL -> partialRequests++;
                case UNAVAILABLE -> unmeasuredRequests++;
            }
            gcPauses += usage.gcPauses();
            if (usage.gcPauses() > 0) {
                requestsWithGcPause++;
            }
        }

        void add(RuntimeEvent event, int status) {
            // A route's first request is its cold one, which loads classes and fills caches.
            if (latency.count() > 0) {
                warmLatency.recordNanos(event.durationNanos());
            }
            latency.recordNanos(event.durationNanos());
            if (status >= 100 && status < 600) {
                statusClasses[status / 100 - 1]++;
            }
        }

        void fold(PendingRequest children, String label, JournalAggregates aggregates) {
            for (int i = 0; i < SOURCES; i++) {
                childCounts[i] += children.counts[i];
                childNanos[i] += children.nanos[i];
            }
            connectionWaitNanos += children.connectionWaitNanos;
            cacheMisses += children.cacheMisses;
            aiTokens += children.aiTokens;
            children.statements.forEach((fingerprint, count) -> statements.get(fingerprint)[0] += count);
            for (String groupId : children.exceptionGroups) {
                aggregates.exceptionGroups.get(groupId).routes.get(label)[0]++;
            }
        }

        RouteStats stats(String route) {
            Map<String, Long> statementCounts = new LinkedHashMap<>();
            statements.entries().forEach((fingerprint, count) -> statementCounts.put(fingerprint, count[0]));
            return new RouteStats(
                    route,
                    latency.count(),
                    List.of(statusClasses[0], statusClasses[1], statusClasses[2], statusClasses[3], statusClasses[4]),
                    latency.copy(),
                    bySource(childCounts),
                    bySource(childNanos),
                    Collections.unmodifiableMap(statementCounts),
                    connectionWaitNanos,
                    new RouteResources(
                            measuredRequests,
                            partialRequests,
                            unmeasuredRequests,
                            cpuNanos,
                            allocatedBytes,
                            gcPauses,
                            requestsWithGcPause,
                            gcPauseNanos),
                    warmLatency.copy(),
                    cacheMisses,
                    aiTokens);
        }
    }

    private static final class Statement {

        private final LatencyHistogram latency = new LatencyHistogram();
        private final CappedMap<long[]> callSites = new CappedMap<>(MAX_CALL_SITES, () -> new long[1]);
        private long failures;

        void add(RuntimeEvent event, SqlPayload sql) {
            latency.recordNanos(event.durationNanos());
            if (sql.failed()) {
                failures++;
            }
            if (sql.callSite() != null) {
                callSites.get(sql.callSite())[0]++;
            }
        }

        StatementStats stats(String fingerprint) {
            Map<String, Long> sites = new LinkedHashMap<>();
            callSites.entries().forEach((site, count) -> sites.put(site, count[0]));
            return new StatementStats(
                    fingerprint, latency.count(), failures, latency.copy(), Collections.unmodifiableMap(sites));
        }
    }

    private static final class ExceptionGroup {

        private final CappedMap<long[]> routes = new CappedMap<>(MAX_ROUTES_PER_EXCEPTION_GROUP, () -> new long[1]);
        private long count;
        private String exceptionClass;
        private String signature;

        void add(ExceptionPayload exception) {
            count++;
            if (exceptionClass == null) {
                exceptionClass = exception.exceptionClass();
            }
            if (signature == null) {
                signature = exception.signature();
            }
        }

        ExceptionGroupStats stats(String groupId) {
            Map<String, Long> byRoute = new LinkedHashMap<>();
            routes.entries().forEach((route, routeCount) -> byRoute.put(route, routeCount[0]));
            return new ExceptionGroupStats(
                    groupId, exceptionClass, signature, count, Collections.unmodifiableMap(byRoute));
        }
    }

    private static final class TransactionalMethod {

        private final LatencyHistogram latency = new LatencyHistogram();
        private long rollbacks;

        void add(RuntimeEvent event, TransactionPayload transaction) {
            latency.recordNanos(event.durationNanos());
            if (transaction.rolledBack()) {
                rollbacks++;
            }
        }

        TransactionalMethodStats stats(String method) {
            return new TransactionalMethodStats(method, latency.count(), rollbacks, latency.copy());
        }
    }

    private static final class ThreadFamily {

        private final long[] counts = new long[SOURCES];
        private final long[] nanos = new long[SOURCES];

        void add(RuntimeEvent event) {
            counts[event.source().ordinal()]++;
            nanos[event.source().ordinal()] += Math.max(0, event.durationNanos());
        }

        ThreadFamilyStats stats(String family) {
            return new ThreadFamilyStats(family, bySource(counts), bySource(nanos));
        }
    }

    /**
     * Every aggregate at one instant, the run's observed edges, and the events each dimension routed to its
     * {@code Other} bucket, or, under {@value #EDGES}, the edge observations not counted.
     */
    public record AggregatesSnapshot(
            List<RouteStats> routes,
            List<StatementStats> statements,
            List<ExceptionGroupStats> exceptionGroups,
            List<TransactionalMethodStats> transactionalMethods,
            List<ThreadFamilyStats> threadFamilies,
            List<ObservedEdge> edges,
            RunStats run,
            Map<String, Long> overflowed) {

        public AggregatesSnapshot {
            edges = List.copyOf(edges);
            routes = List.copyOf(routes);
            statements = List.copyOf(statements);
            exceptionGroups = List.copyOf(exceptionGroups);
            transactionalMethods = List.copyOf(transactionalMethods);
            threadFamilies = List.copyOf(threadFamilies);
            overflowed = Collections.unmodifiableMap(new LinkedHashMap<>(overflowed));
        }
    }

    /**
     * One route, keyed as {@code METHOD route}: its requests, status classes ({@code 1xx} to {@code 5xx}), latency, the count and time of its
     * requests' children per source, how many statements each fingerprint ran in it, and how long its requests waited
     * to obtain database connections. The time of its {@code CONNECTION} children is how long they held them.
     * {@code resources} is what its requests' segments measured (§5.11). {@code warmLatency} leaves out the route's
     * first, cold request, and {@code cacheMisses} and {@code aiTokens} sum its requests' cache misses and model
     * tokens, for the run comparison (§5.8).
     */
    public record RouteStats(
            String route,
            long requests,
            List<Long> statusClasses,
            LatencyHistogram latency,
            Map<JournalSource, Long> childCounts,
            Map<JournalSource, Long> childNanos,
            Map<String, Long> statements,
            long connectionWaitNanos,
            RouteResources resources,
            LatencyHistogram warmLatency,
            long cacheMisses,
            long aiTokens) {

        public RouteStats {
            resources = resources == null ? RouteResources.NONE : resources;
            warmLatency = warmLatency == null ? new LatencyHistogram() : warmLatency;
        }
    }

    /**
     * What a route's requests' segments measured ({@code docs/PLAN-v2.md} §5.11). CPU time and allocated bytes sum only
     * the {@code measuredRequests}, whose every segment was measured, so their averages are exact; requests with some
     * or no segments measured, such as those on virtual threads, are counted, never summed as zero.
     *
     * @param measuredRequests requests whose every segment was measured
     * @param partialRequests requests with some segments the JVM did not measure
     * @param unmeasuredRequests requests with no measured segment
     * @param cpuNanos CPU time of the measured requests
     * @param allocatedBytes bytes allocated by the measured requests
     * @param gcPauses pause collections that completed during the route's requests
     * @param requestsWithGcPause requests during which at least one pause collection completed
     * @param gcPauseNanos the pauses of those collections, joined by id from the {@code GC} events received so far
     */
    public record RouteResources(
            long measuredRequests,
            long partialRequests,
            long unmeasuredRequests,
            long cpuNanos,
            long allocatedBytes,
            long gcPauses,
            long requestsWithGcPause,
            long gcPauseNanos) {

        /** A route whose requests carried no measurement, as when the {@code resources} source is off. */
        public static final RouteResources NONE = new RouteResources(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** One literal-free statement fingerprint: its executions, failures, latency, and executions per call site. */
    public record StatementStats(
            String fingerprint,
            long executions,
            long failures,
            LatencyHistogram latency,
            Map<String, Long> callSites) {}

    /**
     * One exception group: its exception class, its cross-run signature (or {@code null}), its occurrences, and its
     * occurrences per route.
     */
    public record ExceptionGroupStats(
            String groupId, String exceptionClass, String signature, long occurrences, Map<String, Long> routes) {}

    /** One transactional method: its transactions, rollbacks, and latency. */
    public record TransactionalMethodStats(
            String method, long transactions, long rollbacks, LatencyHistogram latency) {}

    /** One family of threads that ran work outside any request or execution: its events and time per source. */
    public record ThreadFamilyStats(String family, Map<JournalSource, Long> events, Map<JournalSource, Long> nanos) {}

    /**
     * The run: its events and time per source, its first and last event, its requests and failed ({@code 5xx})
     * requests, and the requests whose children could not be attributed to a route.
     */
    public record RunStats(
            Map<JournalSource, Long> events,
            Map<JournalSource, Long> nanos,
            Long firstEpochMillis,
            Long lastEpochMillis,
            long requests,
            long failedRequests,
            long unattributedRequests,
            long openRequests) {}
}
