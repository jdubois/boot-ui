package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsExcludedMethodDto;
import io.github.jdubois.bootui.core.dto.CodePathsMethodDto;
import io.github.jdubois.bootui.core.dto.CodePathsNodeDto;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteDto;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsStatusDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Code Paths for one application ({@code docs/PLAN-v2.md} §5.14, M5-4a and M5-4b), shared by every adapter: for this
 * run's claim on the BootUI agent, routes the {@code code-paths} sensor's fragments from the claim's
 * {@link AgentRecordDrainer} into request trees ({@link RequestTreeStore}), merges each settled tree into its route's
 * tree ({@link RouteTrees}), and decides the sensor's adaptive exclusion ({@link AdaptiveExclusion}), writing each
 * excluded method back to the bridge through the claim. Fragments of another claim generation, such as a previous run's
 * still queued, are dropped and counted; a new claim generation starts new trees. Its reads serve the Code Paths panel,
 * {@code get_code_paths}, and {@code route-time-breakdown}'s handler split, and start no scan, network call, or
 * mutation.
 */
public final class CodePathsService implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CodePathsService.class.getName());

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final LongSupplier nanoTime;
    private volatile Function<Set<String>, Map<String, RequestOutcome>> outcomes = ids -> Map.of();
    private volatile Predicate<String> assemblyOnly = AgentCodePaths::isAssemblyOnly;

    /** Route-tree nodes returned when a read asks for none. */
    public static final int DEFAULT_LIMIT = 200;

    /** The most route-tree nodes one read returns. */
    public static final int MAX_LIMIT = 500;

    /** The depth a route-tree read returns when it asks for none. */
    public static final int DEFAULT_DEPTH = 8;

    /** The deepest level a route-tree read returns: the request tree's depth and an asynchronous level. */
    public static final int MAX_DEPTH = RequestTreeBuilder.MAX_DEPTH + 1;

    /** The most routes {@code get_code_paths} lists. */
    public static final int MAX_AGENT_LIMIT = 50;

    /** Why the sensor excludes a method, as {@link AdaptiveExclusion} decides. */
    static final String EXCLUDED_REASON =
            "Called more than 50,000 times a second under 2 µs each: its time stays in its"
                    + " caller for the rest of the run.";

    static final String LIMITATION_SCOPE = "Times come from the BootUI agent's code-paths sensor on application bean"
            + " methods: a JDK, framework, or library method shows only as its caller's self time.";

    static final String LIMITATION_CALLS = "A method's self time includes the SQL, REST client, cache, and AI calls it"
            + " waited on: their call sites are not stamped yet.";

    static final String LIMITATION_RUN = "Each route's first recorded request, the first whose tree settled, is kept"
            + " apart; route trees cover this run only, from request trees settled about two seconds after their last"
            + " fragment.";

    static final String LIMITATION_PERCENTILES = "A node's median and 95th percentile are approximate (≈): interpolated"
            + " within log2 buckets of per-request time and clamped to the node's least and most time.";

    static final String LIMITATION_ASSEMBLY = "A route marked assembly only has a handler that ran on an event loop,"
            + " returned a reactive or asynchronous result, or BootUI could not tell where its work ran: its tree times"
            + " the handler's assembly, not the work that ran later or elsewhere, and route-time-breakdown does not split"
            + " its handler.";

    private final Object lock = new Object();
    private Run run;
    private boolean closed;

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param unavailable why the code-paths sensor does not record for this application, {@code null} when it does, such
     *     as {@link JavaAgentService#codePathsUnavailableReason()}
     */
    public CodePathsService(AgentBridgeAccess access, Supplier<AgentClaim> claims, Supplier<String> unavailable) {
        this(access, claims, unavailable, System::nanoTime);
    }

    CodePathsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            LongSupplier nanoTime) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.unavailable = unavailable == null ? () -> null : unavailable;
        this.nanoTime = nanoTime;
    }

    /**
     * Installs how a settling request's route and outcome are named, for its exemplars, such as
     * {@link JournalRequestOutcomes#of}.
     */
    public void setRequestOutcomes(Function<Set<String>, Map<String, RequestOutcome>> requestOutcomes) {
        this.outcomes = requestOutcomes == null ? ids -> Map.of() : requestOutcomes;
    }

    /**
     * Every request's handler only assembled its result: Spring WebFlux's configuration installs it once for the whole
     * stack ({@link #setAssemblyOnly}), so no request is marked on the event loop.
     */
    public static final Predicate<String> EVERY_REQUEST = requestId -> true;

    /**
     * Installs which requests' handlers only assembled their result, such as {@link AgentCodePaths#isAssemblyOnly}, the
     * default, or {@link #EVERY_REQUEST} on a stack whose handlers all do: their trees are marked assembly only.
     */
    public void setAssemblyOnly(Predicate<String> assemblyOnly) {
        this.assemblyOnly = assemblyOnly == null ? requestId -> false : assemblyOnly;
    }

    /** Whether the tree of the request {@code requestId} is marked assembly only. */
    public boolean isAssemblyOnly(String requestId) {
        try {
            return assemblyOnly.test(requestId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Routes this run's fragments, once the claim is armed and the adapter's engine is ready: the adapter calls it when
     * its context refreshed or Quarkus started, and a read calls it again. Idempotent per claim generation. Never
     * throws.
     */
    public void start() {
        try {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                AgentClaim claim = claims.get();
                if (claim == null
                        || !claim.armed()
                        || claim.generation() == null
                        || !claim.sensors().codePaths()
                        || !access.codePathsSupported()) {
                    return;
                }
                if (run != null && run.generation == claim.generation()) {
                    return;
                }
                if (run != null) {
                    run.close();
                }
                run = new Run(claim);
                run.start();
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not start Code Paths", ex);
        }
    }

    /** Stops routing this run's fragments. Idempotent; the service starts nothing afterwards. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (run != null) {
                run.close();
                run = null;
            }
        }
    }

    /** Why the sensor does not record for this application, or {@code null} when it does. */
    public String unavailableReason() {
        try {
            return unavailable.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The tree of the request {@code requestId} (16 hexadecimal digits), open or kept, after draining what is waiting;
     * {@code null} when this run has none.
     */
    public RequestTree tree(String requestId) {
        Run current = current();
        if (current == null || requestId == null) {
            return null;
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.tree(requestId);
        }
    }

    /** This run's settled trees kept as recent, newest first, after draining what is waiting. */
    public List<RequestTree> recent() {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.recent();
        }
    }

    /** A route's exemplar trees in this run: its slowest, then its latest failed. */
    public List<RequestTree> exemplars(String route) {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.exemplars(route);
        }
    }

    /** The methods adaptively excluded in this run, as {@code class#name+descriptor} keys, in exclusion order. */
    public List<String> excludedMethods() {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        List<Integer> ids;
        synchronized (lock) {
            ids = current.exclusion.excluded();
        }
        List<String> keys = new ArrayList<>();
        for (int id : ids) {
            String[] key = access.methodKeys(id, 1);
            keys.add(key.length == 1 && key[0] != null ? key[0] : "#" + id);
        }
        return keys;
    }

    // --- M5-4b reads
    // --------------------------------------------------------------------------------------------------

    /** The panel's summary: the routes with a tree, slowest warm median first, the sensor's status, and exclusions. */
    public CodePathsReport report() {
        String reason = unavailableReason();
        if (reason != null) {
            return CodePathsReport.unavailable(reason);
        }
        Run current = settledRun();
        if (current == null) {
            return new CodePathsReport(true, null, null, List.of(), List.of(), List.of(LIMITATION_SCOPE));
        }
        List<CodePathsExcludedMethodDto> excluded = new ArrayList<>();
        for (String key : excludedMethods()) {
            excluded.add(new CodePathsExcludedMethodDto(key, EXCLUDED_REASON));
        }
        synchronized (lock) {
            List<CodePathsRouteDto> routes = new ArrayList<>();
            boolean assembly = false;
            for (RouteTree tree : CodePathsViews.ranked(current.routes.routes())) {
                routes.add(CodePathsViews.route(tree, current::key));
                assembly |= tree.assemblyOnly();
            }
            List<String> limitations = limitations(current, assembly);
            return new CodePathsReport(true, null, status(current), routes, excluded, limitations);
        }
    }

    /**
     * One route's tree, paged by depth: the nodes at most {@code depth} levels below the request ({@value
     * #DEFAULT_DEPTH} by default, at most {@value #MAX_DEPTH}), depth first and slowest child first, then
     * {@code offset} and {@code limit} ({@value #DEFAULT_LIMIT} by default, at most {@value #MAX_LIMIT}).
     */
    public CodePathsRouteTreeReport routeTree(String route, Integer depth, Integer offset, Integer limit) {
        int maxDepth = depth == null || depth < 0 ? DEFAULT_DEPTH : Math.min(depth, MAX_DEPTH);
        int first = offset == null || offset < 0 ? 0 : offset;
        int max = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String reason = unavailableReason();
        if (reason != null) {
            return CodePathsRouteTreeReport.empty(false, reason, route, maxDepth, max);
        }
        Run current = settledRun();
        if (current == null) {
            return CodePathsRouteTreeReport.empty(true, null, route, maxDepth, max);
        }
        List<RequestTree> exemplars = exemplars(route);
        synchronized (lock) {
            RouteTree tree = current.routes.route(route);
            if (tree == null) {
                return CodePathsRouteTreeReport.empty(true, null, route, maxDepth, max);
            }
            List<Integer> order = tree.warmRequests() == 0 ? List.of() : CodePathsViews.order(tree, maxDepth);
            int[] childCounts = CodePathsViews.childCounts(tree);
            List<CodePathsNodeDto> nodes = new ArrayList<>();
            Set<Integer> methodIds = new LinkedHashSet<>();
            for (int i = first; i < order.size() && nodes.size() < max; i++) {
                int node = order.get(i);
                nodes.add(CodePathsViews.node(tree, node, current::key, childCounts));
                if (tree.method(node) >= 0) {
                    methodIds.add(tree.method(node));
                }
            }
            List<CodePathsMethodDto> methods =
                    CodePathsViews.methods(tree, methodIds, current.routes.routes(), current::key);
            long warm = tree.warmRequests();
            long handler = tree.handlerNanos();
            List<String> limitations =
                    new ArrayList<>(List.of(LIMITATION_SCOPE, LIMITATION_CALLS, LIMITATION_PERCENTILES));
            if (tree.assemblyOnly()) {
                limitations.add(LIMITATION_ASSEMBLY);
            }
            if (warm == 0) {
                limitations.add("Only the route's first recorded request, kept apart, was recorded: send it again"
                        + " to build its warm tree.");
            }
            if (tree.foldedCalls() > 0) {
                limitations.add(tree.foldedCalls() + " calls found no node left in the route's tree, and their time"
                        + " stays in their callers.");
            }
            return new CodePathsRouteTreeReport(
                    true,
                    null,
                    route,
                    true,
                    tree.assemblyOnly(),
                    warm,
                    tree.hasFirstRequest() ? CodePathsViews.millis(tree.firstRequestNanos()) : null,
                    tree.firstRequestId(),
                    warm == 0 ? 0.0 : CodePathsViews.millis((double) tree.ownNanos() / warm),
                    warm == 0 || handler == 0 ? null : CodePathsViews.millis((double) handler / warm),
                    handler > 0 ? "handler" : "request",
                    maxDepth,
                    nodes,
                    methods,
                    exemplars.stream()
                            .map(RequestTree::requestId)
                            .filter(java.util.Objects::nonNull)
                            .toList(),
                    new PageMetadata(
                            tree.nodeCount(),
                            order.size(),
                            first,
                            max,
                            nodes.size(),
                            first + nodes.size() < order.size()),
                    limitations);
        }
    }

    /** One request's tree while this run keeps it: a recent request, or a route's slowest or latest failed. */
    public CodePathsRequestTreeReport requestTree(String requestId) {
        String reason = unavailableReason();
        if (reason != null) {
            return CodePathsRequestTreeReport.empty(false, reason, requestId);
        }
        Run current = settledRun();
        if (current == null || requestId == null || requestId.isBlank()) {
            return CodePathsRequestTreeReport.empty(true, null, requestId);
        }
        String key = requestId.trim().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            RequestTree tree = current.store.tree(key);
            if (tree == null) {
                return CodePathsRequestTreeReport.empty(true, null, requestId);
            }
            RequestOutcome outcome = current.store.outcome(key);
            String route =
                    outcome == null || RequestOutcome.UNKNOWN_ROUTE.equals(outcome.route()) ? null : outcome.route();
            boolean assembly = isAssemblyOnly(key);
            List<String> limitations = new ArrayList<>(List.of(LIMITATION_SCOPE, LIMITATION_CALLS));
            if (assembly) {
                limitations.add("Its handler ran on an event loop, returned a reactive or asynchronous result, or"
                        + " BootUI could not tell where its work ran: the tree times its assembly, not the work that ran"
                        + " later or elsewhere.");
            }
            if (tree.cut()) {
                limitations.add("A fragment was flushed with calls still open, as an asynchronous handler's is: their"
                        + " time runs to the flush.");
            }
            return new CodePathsRequestTreeReport(
                    true,
                    null,
                    requestId,
                    true,
                    route,
                    assembly,
                    CodePathsViews.millis(tree.durationNanos()),
                    CodePathsViews.millis(tree.asyncNanos()),
                    tree.cut(),
                    tree.droppedCalls(),
                    CodePathsViews.nodes(tree, current::key),
                    CodePathsViews.topMethods(tree, current::key),
                    limitations);
        }
    }

    /**
     * Code Paths for agents: the routes matching {@code query} (blank for every route; else the route, or part of a
     * route or of a top method), slowest warm median first, at most {@code limit} ({@link
     * CodePathsAgentReport#DEFAULT_LIMIT} by default, at most {@value #MAX_AGENT_LIMIT}); with one route listed, its
     * method nodes with the most self time.
     */
    public CodePathsAgentReport agentReport(String query, Integer limit) {
        String asked = query == null ? "" : query.trim();
        int max = limit == null || limit <= 0 ? CodePathsAgentReport.DEFAULT_LIMIT : Math.min(limit, MAX_AGENT_LIMIT);
        CodePathsReport report = report();
        if (!report.available()) {
            return new CodePathsAgentReport(
                    false, report.unavailableReason(), asked, 0, List.of(), 0, List.of(), List.of(), List.of());
        }
        String needle = asked.toLowerCase(Locale.ROOT);
        List<CodePathsRouteDto> matching = new ArrayList<>();
        for (CodePathsRouteDto route : report.routes()) {
            if (route.route().equalsIgnoreCase(asked)) {
                matching.clear();
                matching.add(route);
                break;
            }
            if (needle.isEmpty() || matches(route, needle)) {
                matching.add(route);
            }
        }
        List<CodePathsRouteDto> listed = matching.subList(0, Math.min(max, matching.size()));
        List<CodePathsNodeDto> hottest =
                listed.size() == 1 ? hottest(listed.get(0).route(), max) : List.of();
        List<String> limitations = new ArrayList<>(report.limitations());
        if (!hottest.isEmpty()) {
            limitations.add(LIMITATION_PERCENTILES);
        }
        if (matching.isEmpty() && !report.routes().isEmpty()) {
            limitations.add("No route matched \"" + asked + "\": call get_code_paths without a query to list them.");
        }
        if (report.routes().isEmpty()) {
            limitations.add("No route tree yet: send the route's requests, then wait about two seconds for their trees"
                    + " to settle.");
        }
        return new CodePathsAgentReport(
                true,
                null,
                asked,
                matching.size(),
                List.copyOf(listed),
                matching.size() - listed.size(),
                hottest,
                report.excludedMethods().stream()
                        .map(CodePathsExcludedMethodDto::method)
                        .toList(),
                limitations);
    }

    /** The route's method nodes with the most self time, from its whole tree, at most {@code max}. */
    private List<CodePathsNodeDto> hottest(String route, int max) {
        Run current = settledRun();
        if (current == null) {
            return List.of();
        }
        synchronized (lock) {
            RouteTree tree = current.routes.route(route);
            if (tree == null || tree.warmRequests() == 0) {
                return List.of();
            }
            int[] childCounts = CodePathsViews.childCounts(tree);
            List<CodePathsNodeDto> nodes = new ArrayList<>();
            for (int node : CodePathsViews.hottest(tree, max)) {
                nodes.add(CodePathsViews.node(tree, node, current::key, childCounts));
            }
            return List.copyOf(nodes);
        }
    }

    private static boolean matches(CodePathsRouteDto route, String needle) {
        if (route.route().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return route.topMethods().stream()
                .anyMatch(method -> method.method() != null
                        && method.method().toLowerCase(Locale.ROOT).contains(needle));
    }

    /**
     * What the route's tree says about its handler, for {@code route-time-breakdown}'s split, or {@code null} when the
     * sensor does not record this run or the route has no warm tree.
     */
    public HandlerMethods handlerMethods(String route) {
        if (route == null || unavailableReason() != null) {
            return null;
        }
        Run current = settledRun();
        if (current == null) {
            return null;
        }
        synchronized (lock) {
            RouteTree tree = current.routes.route(route);
            return tree == null ? null : CodePathsViews.handler(tree, current::key);
        }
    }

    /**
     * A cheap fingerprint of what the route trees hold, which changes with every merged tree and claim generation, so
     * a cached projection that read them knows it is stale. 0 when the sensor does not record this run.
     */
    public long routeTreesFingerprint() {
        if (unavailableReason() != null) {
            return 0L;
        }
        Run current = settledRun();
        if (current == null) {
            return 0L;
        }
        synchronized (lock) {
            return current.generation * 1_000_003L + current.routes.version();
        }
    }

    /** The current run, after draining what is waiting and settling the quiet trees; {@code null} when none. */
    private Run settledRun() {
        Run current = current();
        if (current == null) {
            return null;
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
        }
        return current;
    }

    private CodePathsStatusDto status(Run current) {
        return new CodePathsStatusDto(
                current.generation,
                current.fragments,
                current.stale,
                current.malformed,
                current.store.settled(),
                current.routes.merged(),
                current.routes.unrouted(),
                current.routes.routes().size(),
                current.routes.nodes(),
                RouteTrees.MAX_NODES,
                current.routes.routes().stream()
                        .mapToLong(RouteTree::foldedCalls)
                        .sum());
    }

    private List<String> limitations(Run current, boolean assembly) {
        List<String> limitations = new ArrayList<>(List.of(LIMITATION_SCOPE, LIMITATION_CALLS, LIMITATION_RUN));
        if (assembly) {
            limitations.add(LIMITATION_ASSEMBLY);
        }
        long unrouted = current.routes.unrouted();
        if (unrouted > 0) {
            limitations.add(unrouted + (unrouted == 1 ? " request tree is" : " request trees are")
                    + " in no route tree: the journal no longer named its route, or it was BootUI's or no request's.");
        }
        if (current.routes.routesDropped() > 0) {
            limitations.add(current.routes.routesDropped() + " request trees of new routes were left out: the run"
                    + " already holds " + RouteTrees.MAX_ROUTES + " routes or its node budget is spent.");
        }
        return limitations;
    }

    /** This run's counters, for status and tests: JDK types. */
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        Run current = current();
        map.put("generation", current == null ? null : current.generation);
        if (current != null) {
            synchronized (lock) {
                map.put("fragments", current.fragments);
                map.put("staleFragments", current.stale);
                map.put("malformedFragments", current.malformed);
                map.put("openTrees", current.store.openCount());
                map.put("settledTrees", current.store.settled());
                map.put("lateFragments", current.store.late());
                map.put("lateFragmentsDropped", current.store.lateDropped());
                map.put("forgottenTrees", current.store.forgotten());
                map.put("keptNodes", current.store.keptNodes());
                map.put("excludedMethods", current.exclusion.excluded().size());
            }
        }
        return map;
    }

    private Run current() {
        start();
        synchronized (lock) {
            return run;
        }
    }

    /** One run: its claim, drainer route, store, and exclusion. */
    private final class Run implements Consumer<long[]> {

        final AgentClaim claim;
        final long generation;
        final AgentRecordDrainer drainer;
        final RequestTreeStore store;
        final AdaptiveExclusion exclusion = new AdaptiveExclusion();
        final RouteTrees routes = new RouteTrees();
        final Map<Integer, String> keys = new HashMap<>();
        long fragments;
        long stale;
        long malformed;

        Run(AgentClaim claim) {
            this.claim = claim;
            this.generation = claim.generation();
            this.drainer = claim.drainer();
            this.store = new RequestTreeStore(ids -> outcomes.apply(ids));
            this.store.onSettled((tree, outcome) ->
                    routes.add(tree, outcome.route(), tree.requestId() != null && isAssemblyOnly(tree.requestId())));
        }

        /** The key of method {@code id}, {@code class#name+descriptor}, or {@code #id} when the bridge has none. */
        String key(int id) {
            String known = keys.get(id);
            if (known != null) {
                return known;
            }
            String[] key = access.methodKeys(id, 1);
            String resolved = key.length == 1 && key[0] != null ? key[0] : "#" + id;
            if (keys.size() < 1_000_000) {
                keys.put(id, resolved);
            }
            return resolved;
        }

        void start() {
            if (drainer != null) {
                drainer.routeCodePaths(this);
            }
        }

        void drainNow() {
            if (drainer != null) {
                drainer.drainNow();
            }
        }

        void close() {
            if (drainer != null) {
                drainer.unrouteCodePaths(this);
            }
        }

        /** One fragment, on the drain thread. */
        @Override
        public void accept(long[] blob) {
            CodePathFragment fragment = CodePathFragment.decode(blob);
            List<Integer> newly;
            synchronized (lock) {
                if (fragment == null) {
                    malformed++;
                    return;
                }
                if (fragment.generation() != generation) {
                    stale++;
                    return;
                }
                fragments++;
                long now = nanoTime.getAsLong();
                exclusion.record(fragment, now);
                store.add(fragment, now);
                store.settle(now);
                newly = exclusion.evaluate(now);
            }
            for (int id : newly) {
                claim.excludeCodePathsMethod(id);
            }
        }
    }
}
