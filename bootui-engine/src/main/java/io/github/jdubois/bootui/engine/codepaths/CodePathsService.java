package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeanEdgeDto;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
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
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.model.BeanInvocations;
import io.github.jdubois.bootui.engine.model.ClassInvocation;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * mutation. Its method probes ({@link #probes()}, M5-8) are the panel's only actions.
 */
public final class CodePathsService implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CodePathsService.class.getName());

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final LongSupplier nanoTime;
    private volatile RequestOutcomeReader outcomes = ids -> Map.of();
    private volatile Predicate<String> assemblyOnly = AgentCodePaths::isAssemblyOnly;
    private final AgentEvidence evidence;
    private final AgentEvidence.Store store = new Store();
    // What the current run holds, published on every change for the evidence store, which never waits on the lock.
    private volatile long usageBytes;
    private volatile long usageMaxBytes;
    private volatile long usageTrees;
    private volatile long usageRoutes;
    private volatile long usageRouteNodes;
    private volatile long usageIndexBytes;
    private volatile Supplier<StructureSnapshot> structure = () -> null;

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

    /** Why the reads answer nothing while HTTP Exchanges, which owns request routes and outcomes, is not visible. */
    public static final String ROUTES_HIDDEN = "The HTTP Exchanges panel is disabled: Code Paths keys its call trees by"
            + " HTTP route and request, so they are left out until it is enabled.";

    static final String LIMITATION_SCOPE = "Times come from the BootUI agent's code-paths sensor on application bean"
            + " methods: a JDK, framework, or library method shows only as its caller's self time.";

    static final String LIMITATION_CALLS = "SQL, REST client, cache, and AI calls are shown under the innermost"
            + " instrumented method open on their thread when they were issued, and their time is part of its self time."
            + " A statement Hibernate flushes when a transaction commits runs after the @Transactional method returned,"
            + " in the transaction interceptor around it (Spring's TransactionInterceptor, Quarkus's ArC interceptor), so"
            + " it shows under the method that called the @Transactional one, or under no method when no instrumented"
            + " method called it. A call recorded on another thread than the one that issued it, as a WebClient call"
            + " subscribed on another thread or a streaming AI call, carries no stamp and shows under no method.";

    static final String LIMITATION_RUN = "Each route's first recorded request, the first whose tree settled, is kept"
            + " apart; route trees cover this run only, from request trees settled about two seconds after their last"
            + " fragment and once their request's exchange was recorded, as a slow reactive response's is only when it"
            + " completes.";

    static final String LIMITATION_PERCENTILES = "A node's median and 95th percentile are approximate (≈): interpolated"
            + " within log2 buckets of per-request time and clamped to the node's least and most time.";

    static final String LIMITATION_ASSEMBLY = "A route marked assembly only has a handler that ran on an event loop,"
            + " returned a reactive or asynchronous result, or BootUI could not tell where its work ran: its tree times"
            + " the handler's assembly, not the work that ran later or elsewhere, and route-time-breakdown does not split"
            + " its handler.";

    private final Object lock = new Object();
    private final MethodProbeService probes;
    private Run run;
    private boolean closed;

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param unavailable why the code-paths sensor does not record for this application, {@code null} when it does, such
     *     as {@link JavaAgentService#codePathsUnavailableReason()}
     */
    public CodePathsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            AgentEvidence evidence) {
        this(access, claims, unavailable, evidence, System::nanoTime);
    }

    /**
     * @param evidence the agent evidence contract this service's trees are read, cleared, bounded, and counted under
     *     (M5-11)
     */
    CodePathsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            AgentEvidence evidence,
            LongSupplier nanoTime) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.unavailable = unavailable == null ? () -> null : unavailable;
        this.nanoTime = nanoTime;
        this.evidence = java.util.Objects.requireNonNull(evidence, "evidence");
        evidence.register(store);
        this.probes = new MethodProbeService(this.access, this.claims, this::unavailableReason, evidence);
    }

    /**
     * The live exposure policy method probes' argument and return shapes are shown by (M5-8, D44); without one, the
     * policy's default, {@code MASKED}.
     */
    public void setExposure(io.github.jdubois.bootui.spi.ExposurePolicy exposure) {
        probes.setExposure(exposure);
    }

    /** This run's method probes ({@code docs/PLAN-v2.md} M5-8): part of Code Paths, available when it is. */
    public MethodProbeService probes() {
        return probes;
    }

    /**
     * Installs how a settling request's route and outcome are named, for its exemplars, such as
     * {@link JournalRequestOutcomes#of}.
     */
    public void setRequestOutcomes(Function<Set<String>, Map<String, RequestOutcome>> requestOutcomes) {
        this.outcomes = RequestOutcomeReader.of(requestOutcomes);
    }

    /**
     * The read of this service's panels now ({@code docs/PLAN-v2.md} §8, M5-11): the Code Paths panel, and HTTP
     * Exchanges, which owns request routes and outcomes. A caller projecting several reads, as Runtime Insights, resolves
     * it once and passes it to each.
     */
    public AgentEvidence.Read read() {
        return evidence.read(store);
    }

    /**
     * Why the route and request reads answer nothing under {@code read}: the sensor does not record, the Code Paths panel
     * is hidden, or HTTP Exchanges is ({@link #ROUTES_HIDDEN}); else {@code null}.
     */
    private String readReason(AgentEvidence.Read read) {
        String reason = shownReason(read);
        if (reason != null) {
            return reason;
        }
        return read.requests() ? null : ROUTES_HIDDEN;
    }

    /** Why nothing of Code Paths is shown under {@code read}: the sensor does not record, or the panel is hidden. */
    private String shownReason(AgentEvidence.Read read) {
        String reason = unavailableReason();
        return reason != null ? reason : read.hiddenReason();
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

    /**
     * Installs how the application's beans and their declared dependencies are read, such as
     * {@code StructureSnapshots.read} over the Beans panel's provider, for Beans at runtime ({@link #beans()}, M5-4c).
     */
    public void setStructure(Supplier<StructureSnapshot> structure) {
        this.structure = structure == null ? () -> null : structure;
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
                publish(run);
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
        probes.close();
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
     * {@code null} when this run has none, or while its routes are hidden.
     */
    public RequestTree tree(String requestId) {
        Run current = current();
        if (current == null || readReason(read()) != null || requestId == null) {
            return null;
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.tree(requestId);
        }
    }

    /** This run's settled trees kept as recent, newest first, after draining what is waiting; none while hidden. */
    public List<RequestTree> recent() {
        Run current = current();
        if (current == null || readReason(read()) != null) {
            return List.of();
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.recent();
        }
    }

    /** A route's exemplar trees in this run: its slowest, then its latest failed; none while hidden. */
    public List<RequestTree> exemplars(String route) {
        return exemplars(read(), route);
    }

    private List<RequestTree> exemplars(AgentEvidence.Read read, String route) {
        Run current = current();
        if (current == null || readReason(read) != null) {
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
        return report(read());
    }

    private CodePathsReport report(AgentEvidence.Read read) {
        String reason = readReason(read);
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
        AgentEvidence.Read read = read();
        String reason = readReason(read);
        if (reason != null) {
            return CodePathsRouteTreeReport.empty(false, reason, route, maxDepth, max);
        }
        Run current = settledRun();
        if (current == null) {
            return CodePathsRouteTreeReport.empty(true, null, route, maxDepth, max);
        }
        List<RequestTree> exemplars = exemplars(read, route);
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
            limitations.addAll(unstampedLimitations(tree.unplaced(), "warm requests"));
            if (tree.homelessCalls() > 0) {
                limitations.add(tree.homelessCalls() + " stamped " + (tree.homelessCalls() == 1 ? "call" : "calls")
                        + " of the warm requests ran in work an executor ran whose node found no room in the route's"
                        + " tree, so they show under no method.");
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
        String reason = readReason(read());
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
            limitations.addAll(unstampedLimitations(tree.unplaced(), "request"));
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
        CodePathsReport report = report(read());
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

    /** What the recorded calls without a node mean, for a route's warm requests or one request, by why. */
    static List<String> unstampedLimitations(UnplacedCalls unplaced, String scope) {
        List<String> limitations = new ArrayList<>();
        if (unplaced == null) {
            return limitations;
        }
        long other = unplaced.otherThread();
        if (other > 0) {
            limitations.add(calls(other, "recorded call") + " of the " + scope + " carried no stamp: "
                    + (other == 1 ? "it was" : "they were") + " recorded on a thread where the code-paths sensor"
                    + " recorded no open method of the request, as another thread than the one that issued "
                    + (other == 1 ? "it" : "them") + " (a WebClient call subscribed elsewhere, a streaming AI call, an"
                    + " asynchronous cache access), so " + (other == 1 ? "it shows" : "they show")
                    + " under no method; a method that"
                    + " waited for " + (other == 1 ? "it keeps its" : "them keeps their") + " time in its own time.");
        }
        long outside = unplaced.outside();
        if (outside > 0) {
            limitations.add(calls(outside, "recorded call") + " of the " + scope + " ran on the request's thread while"
                    + " no instrumented method was open: in a filter such as Spring Security's, while the response was"
                    + " written (as a lazy load under open session in view during serialization), or in a transaction"
                    + " commit after the outermost instrumented method returned. "
                    + (outside == 1 ? "It shows" : "They show")
                    + " under no method, and " + (outside == 1 ? "its" : "their")
                    + " time is in no method's self time.");
        }
        long missing = unplaced.missing();
        if (missing > 0) {
            limitations.add(calls(missing, "stamped call") + " of the " + scope + " named a fragment the tree did not"
                    + " hold when " + (missing == 1 ? "it was" : "they were") + " attached: the agent dropped it, the"
                    + " request had more fragments than a tree tracks, or it had not arrived when the tree settled.");
        }
        long late = unplaced.late();
        if (late > 0) {
            limitations.add(calls(late, "stamped call") + " of the " + scope + " ran in a fragment that arrived after"
                    + " the tree settled: the fragment's methods are in the tree, but "
                    + (late == 1 ? "this call is" : "these calls are") + " not placed under them.");
        }
        long overflow = unplaced.overflow();
        if (overflow > 0) {
            limitations.add(calls(overflow, "stamped call") + " of the " + scope + " came past the "
                    + String.format(Locale.ROOT, "%,d", RequestOutcome.MAX_CALLS) + " a request places, and "
                    + (overflow == 1 ? "shows" : "show") + " under no method.");
        }
        return limitations;
    }

    private static String calls(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
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
     * sensor does not record this run, HTTP Exchanges is not visible, or the route has no warm tree.
     */
    public HandlerMethods handlerMethods(String route) {
        return handlerMethods(read(), route);
    }

    /** {@link #handlerMethods(String)} under {@code read}, a read of the panels the caller resolved once. */
    public HandlerMethods handlerMethods(AgentEvidence.Read read, String route) {
        if (route == null || readReason(read) != null) {
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
     * The calls between application classes in this run's route trees ({@code docs/PLAN-v2.md} §5.14, M5-4c): each
     * method node under another method node, by their classes, with the calls their warm requests and each route's first
     * request counted, most first. Work an executor ran is left out, as its caller is the executor. Empty when the sensor
     * does not record this run.
     */
    public List<ClassInvocation> invocations() {
        return invocations(read());
    }

    /**
     * {@link #invocations()} under {@code read}: empty while the Code Paths panel is hidden too, as the calls are its
     * evidence.
     */
    public List<ClassInvocation> invocations(AgentEvidence.Read read) {
        if (shownReason(read) != null) {
            return List.of();
        }
        Run current = settledRun();
        if (current == null) {
            return List.of();
        }
        Map<List<String>, long[]> calls = new LinkedHashMap<>();
        synchronized (lock) {
            for (RouteTree tree : current.routes.routes()) {
                for (int node = 1; node < tree.nodeCount(); node++) {
                    int method = tree.method(node);
                    int parent = tree.method(tree.parent(node));
                    if (method < 0 || parent < 0 || tree.calls(node) == 0) {
                        continue;
                    }
                    String caller = CodePathsViews.className(current.key(parent));
                    String callee = CodePathsViews.className(current.key(method));
                    if (caller == null || callee == null || caller.equals(callee)) {
                        continue;
                    }
                    calls.computeIfAbsent(List.of(caller, callee), ignored -> new long[1])[0] += tree.calls(node);
                }
                // The route's first request, kept apart from its warm tree, called its beans too.
                for (Map.Entry<Long, Long> pair : tree.firstRequestPairs().entrySet()) {
                    String caller = CodePathsViews.className(current.key((int) (pair.getKey() >>> 32)));
                    String callee = CodePathsViews.className(
                            current.key((int) pair.getKey().longValue()));
                    if (caller == null || callee == null || caller.equals(callee)) {
                        continue;
                    }
                    calls.computeIfAbsent(List.of(caller, callee), ignored -> new long[1])[0] += pair.getValue();
                }
            }
        }
        List<ClassInvocation> invocations = new ArrayList<>();
        calls.forEach((pair, count) -> invocations.add(new ClassInvocation(pair.get(0), pair.get(1), count[0])));
        invocations.sort((left, right) -> Long.compare(right.calls(), left.calls()));
        return List.copyOf(invocations);
    }

    /**
     * Beans at runtime ({@code docs/PLAN-v2.md} §5.14, M5-4c): the calls between application beans observed in this
     * run's route trees, beside the dependencies the beans declare, with how many declared dependencies were not called
     * in this run among those whose calls the sensor would observe: both beans' classes instrumented, and none of their
     * methods adaptively excluded.
     */
    public CodePathsBeansReport beans() {
        AgentEvidence.Read read = read();
        String reason = shownReason(read);
        if (reason != null) {
            return CodePathsBeansReport.unavailable(reason);
        }
        StructureSnapshot snapshot;
        try {
            snapshot = structure.get();
        } catch (RuntimeException ex) {
            snapshot = null;
        }
        List<String> limitations = new ArrayList<>(List.of(LIMITATION_BEANS));
        if (snapshot == null || snapshot.beansUnavailable() != null) {
            limitations.add(
                    snapshot == null || snapshot.beansUnavailable() == null
                            ? "The application's beans could not be read, so neither their declared dependencies nor the beans"
                                    + " of the observed calls are known."
                            : snapshot.beansUnavailable());
            return new CodePathsBeansReport(true, null, false, List.of(), 0, 0, 0, 0, limitations);
        }
        Run current = settledRun();
        Set<String> traced = current == null ? Set.of() : current.claim.beanClasses();
        // A class with an adaptively excluded method: calls into it, or from it, stay in another method's time.
        Set<String> excluded = new HashSet<>();
        for (String key : excludedMethods()) {
            String type = BeanInvocations.userClass(CodePathsViews.className(key));
            if (type != null) {
                excluded.add(type);
            }
        }
        BeanInvocations.Resolved observed = BeanInvocations.resolve(snapshot.beans(), invocations(read));
        Map<String, StructureSnapshot.Bean> byName = new LinkedHashMap<>();
        for (StructureSnapshot.Bean bean : snapshot.beans()) {
            byName.putIfAbsent(bean.name(), bean);
        }
        Map<List<String>, Long> calls = new LinkedHashMap<>();
        for (BeanInvocations.Edge edge : observed.edges()) {
            calls.put(List.of(edge.from(), edge.to()), edge.calls());
        }
        Set<List<String>> declared = new LinkedHashSet<>();
        for (StructureSnapshot.Bean bean : snapshot.beans()) {
            for (String dependency : bean.dependencies()) {
                if (byName.containsKey(dependency) && !dependency.equals(bean.name())) {
                    declared.add(List.of(bean.name(), dependency));
                }
            }
        }
        List<CodePathsBeanEdgeDto> edges = new ArrayList<>();
        for (Map.Entry<List<String>, Long> entry : calls.entrySet()) {
            edges.add(edge(entry.getKey(), byName, declared.contains(entry.getKey()), entry.getValue(), null));
        }
        int notCalled = 0;
        List<CodePathsBeanEdgeDto> uncalled = new ArrayList<>();
        for (List<String> pair : declared) {
            if (calls.containsKey(pair)) {
                continue;
            }
            String why = unobservable(byName.get(pair.get(0)), byName.get(pair.get(1)), traced, excluded);
            if (why == null) {
                notCalled++;
            }
            uncalled.add(edge(pair, byName, true, 0L, why));
        }
        uncalled.sort((left, right) -> {
            int byTraced = Boolean.compare(right.observable(), left.observable());
            if (byTraced != 0) {
                return byTraced;
            }
            int byFrom = left.from().compareTo(right.from());
            return byFrom != 0 ? byFrom : left.to().compareTo(right.to());
        });
        edges.addAll(uncalled);
        int omitted = Math.max(0, edges.size() - CodePathsBeansReport.MAX_EDGES);
        if (current == null || calls.isEmpty()) {
            limitations.add("No call between beans observed yet: send the application's requests, then wait about two"
                    + " seconds for their trees to settle.");
        }
        if (observed.unmappedCalls() > 0) {
            limitations.add(observed.unmappedCalls() + " observed calls are between classes that are not each exactly"
                    + " one bean's, so they show in no edge.");
        }
        return new CodePathsBeansReport(
                true,
                null,
                true,
                edges.subList(0, edges.size() - omitted),
                calls.size(),
                declared.size(),
                notCalled,
                omitted,
                limitations);
    }

    /**
     * Why a call from {@code from} to {@code to} would not be observed, or {@code null} when it would: both classes are
     * instrumented bean classes, and neither has an adaptively excluded method.
     */
    static String unobservable(
            StructureSnapshot.Bean from, StructureSnapshot.Bean to, Set<String> traced, Set<String> excluded) {
        String fromType = from == null ? null : BeanInvocations.userClass(from.type());
        String toType = to == null ? null : BeanInvocations.userClass(to.type());
        if (toType == null || !traced.contains(toType)) {
            return "The called bean's class is not one the code-paths sensor instruments, as a repository a framework"
                    + " generates, so calls into it are not observed.";
        }
        if (fromType == null || !traced.contains(fromType)) {
            return "The calling bean's class is not one the code-paths sensor instruments, so its calls are not"
                    + " observed: they show under its nearest instrumented caller.";
        }
        if (excluded.contains(toType) || excluded.contains(fromType)) {
            return "A method of " + (excluded.contains(toType) ? "the called" : "the calling") + " bean was adaptively"
                    + " excluded, so its calls stay in its caller's time and a call between them may not be observed.";
        }
        return null;
    }

    private static CodePathsBeanEdgeDto edge(
            List<String> pair,
            Map<String, StructureSnapshot.Bean> byName,
            boolean declared,
            long calls,
            String unobservableReason) {
        StructureSnapshot.Bean from = byName.get(pair.get(0));
        StructureSnapshot.Bean to = byName.get(pair.get(1));
        return new CodePathsBeanEdgeDto(
                pair.get(0),
                from == null ? null : from.type(),
                pair.get(1),
                to == null ? null : to.type(),
                declared,
                calls > 0,
                calls,
                unobservableReason == null,
                unobservableReason);
    }

    static final String LIMITATION_BEANS = "Calls come from this run's route trees, first requests included: a declared"
            + " dependency not called in this run may run on a path no request took, in work an executor ran, or outside"
            + " a request. A dependency is \"not called in this run\" only when a call would have been observed: both"
            + " beans' classes are ones the code-paths sensor instruments, and none of their methods was adaptively"
            + " excluded; otherwise it is not observable, as with a repository whose class a framework generates.";

    /**
     * The key of method {@code id}, {@code class#name+descriptor}, as a code-paths stamp names it, or {@code null} when
     * the sensor does not record for this application or the bridge does not know it: {@code repeated-selects} names
     * the method that issued a statement with it.
     */
    public String methodKey(int id) {
        return methodKey(read(), id);
    }

    /** {@link #methodKey(int)} under {@code read}: {@code null} while the Code Paths panel is hidden too. */
    public String methodKey(AgentEvidence.Read read, int id) {
        if (id < 0 || shownReason(read) != null) {
            return null;
        }
        try {
            String[] key = access.methodKeys(id, 1);
            return key.length == 1 ? key[0] : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The method that issued the calls a code-paths stamp names with method {@code id} on {@code route}, for {@code
     * repeated-selects} ({@code docs/PLAN-v2.md} §5.14, M5-4c): that method, unless it is a repository or DAO method of
     * the application's own (a repository bean, or a class named as one), in which case the first method above it in the
     * route's tree that is not, through the node of {@code id} with the most SQL statements. {@code null} when the sensor
     * does not record for this application or the bridge does not know the method.
     */
    public IssuingMethod issuingMethod(String route, int id) {
        return issuingMethod(read(), route, id);
    }

    /**
     * {@link #issuingMethod(String, int)} under {@code read}: {@code null} while the Code Paths panel is hidden, and
     * the method alone, unclimbed, while HTTP Exchanges is, as the route's tree is its evidence.
     */
    public IssuingMethod issuingMethod(AgentEvidence.Read read, String route, int id) {
        String key = methodKey(read, id);
        if (key == null) {
            return null;
        }
        Run current = readReason(read) == null ? settledRun() : null;
        if (current == null) {
            return IssuingMethod.of(key);
        }
        Set<String> repositories = current.repositories();
        if (!repository(IssuingMethod.classOf(key), repositories)) {
            return new IssuingMethod(key, null, false);
        }
        synchronized (lock) {
            RouteTree tree = route == null ? null : current.routes.route(route);
            int best = -1;
            long most = -1;
            for (int node = 1; tree != null && node < tree.nodeCount(); node++) {
                if (tree.method(node) != id) {
                    continue;
                }
                long sql = tree.ioCalls(node, CodePathStamps.SQL);
                if (sql > most) {
                    most = sql;
                    best = node;
                }
            }
            for (int node = best < 0 ? -1 : tree.parent(best); node > 0; node = tree.parent(node)) {
                int method = tree.method(node);
                if (method < 0) {
                    break;
                }
                String above = current.key(method);
                if (!repository(IssuingMethod.classOf(above), repositories)) {
                    return new IssuingMethod(above, key, false);
                }
            }
        }
        return new IssuingMethod(key, null, true);
    }

    /** Whether {@code className} is a repository bean's class, or named as a repository or DAO class. */
    private static boolean repository(String className, Set<String> repositories) {
        return className != null
                && (repositories.contains(BeanInvocations.userClass(className))
                        || IssuingMethod.repositoryClass(className));
    }

    /**
     * Which routes' requests executed the methods whose keys {@code wanted} accepts ({@code docs/PLAN-v2.md} §5.7, §5.17,
     * M5-7a), from each route's own trees, after draining what is waiting and settling the quiet trees; unavailable
     * while the sensor does not record this run or HTTP Exchanges is not visible. Keys are resolved outside the lock, in
     * one bridge read, and only the matching methods are looked up in each route's table. Never throws.
     */
    public MethodRoutes methodRoutes(Predicate<String> wanted) {
        return methodRoutes(read(), wanted);
    }

    /** {@link #methodRoutes(Predicate)} under {@code read}, a read of the panels the caller resolved once (M5-11). */
    public MethodRoutes methodRoutes(AgentEvidence.Read read, Predicate<String> wanted) {
        try {
            String reason = readReason(read);
            if (reason != null) {
                return MethodRoutes.unavailable(reason);
            }
            Run current = settledRun();
            if (current == null) {
                return MethodRoutes.unavailable("The code-paths sensor has not recorded this run yet.");
            }
            // Only the ids the route tables hold are resolved, through the run's key cache.
            Map<Integer, String> matched = new HashMap<>();
            List<Integer> excludedIds;
            synchronized (lock) {
                Set<Integer> ids = new HashSet<>();
                for (RouteTree tree : current.routes.routes()) {
                    for (int id : tree.executedMethods()) {
                        ids.add(id);
                    }
                }
                for (int id : current.routes.unroutedMethods()) {
                    ids.add(id);
                }
                excludedIds = current.exclusion.excluded();
                ids.addAll(excludedIds);
                for (int id : ids) {
                    String key = current.key(id);
                    if (!key.startsWith("#") && wanted.test(key)) {
                        matched.put(id, key);
                    }
                }
            }
            Set<String> excluded = new LinkedHashSet<>();
            for (int id : excludedIds) {
                String key = matched.get(id);
                if (key != null) {
                    excluded.add(key);
                }
            }
            Map<String, Map<String, Long>> byKey = new LinkedHashMap<>();
            Map<String, Long> unrouted = new LinkedHashMap<>();
            Map<String, MethodRoutes.RouteEvidence> evidence = new LinkedHashMap<>();
            List<String> limitations = new ArrayList<>();
            synchronized (lock) {
                for (RouteTree tree : current.routes.routes()) {
                    for (Map.Entry<Integer, String> method : matched.entrySet()) {
                        long requests = tree.executedRequests(method.getKey());
                        if (requests > 0) {
                            byKey.computeIfAbsent(method.getValue(), ignored -> new LinkedHashMap<>())
                                    .merge(tree.route(), requests, Long::sum);
                        }
                    }
                    evidence.put(tree.route(), evidence(tree));
                }
                for (Map.Entry<Integer, String> method : matched.entrySet()) {
                    long trees = current.routes.unroutedExecutions(method.getKey());
                    if (trees > 0) {
                        unrouted.merge(method.getValue(), trees, Long::sum);
                    }
                }
                int settling = current.store.openCount() + current.store.unresolvedCount();
                if (settling > 0) {
                    limitations.add(settling + (settling == 1 ? " request tree is" : " request trees are")
                            + " still settling, about two seconds after their last fragment or until their exchange is"
                            + " recorded: their requests are not counted yet.");
                }
                if (current.clears > 0) {
                    limitations.add(RECORDING_CLEARED);
                }
                if (current.malformed > 0) {
                    limitations.add(current.malformed + " code-paths fragments could not be read in this run.");
                }
                if (current.routes.routesDropped() > 0 || current.routes.unroutedPartial()) {
                    limitations.add("The run's route trees reached their bounds: some requests' methods are counted"
                            + " under no route.");
                }
            }
            long dropped = current.agentDropped() - current.droppedAtStart;
            if (dropped > 0) {
                limitations.add("The agent dropped " + dropped + " code-paths fragments in this run, as its fragment"
                        + " pool or queue was full: their methods are counted under no route.");
            }
            return new MethodRoutes(
                    null, byKey, unrouted, evidence, excluded, current.claim.beanClasses(), limitations);
        } catch (RuntimeException ex) {
            log.log(Level.FINE, "BootUI could not read the methods of this run's route trees", ex);
            return MethodRoutes.unavailable(
                    MethodRoutes.READ_FAILED + ex.getClass().getSimpleName() + ".");
        }
    }

    private static MethodRoutes.RouteEvidence evidence(RouteTree tree) {
        List<String> reasons = new ArrayList<>();
        if (tree.methodsPartial()) {
            reasons.add("its executed-method table reached its bound");
        }
        if (tree.incompleteRequests() > 0) {
            reasons.add(tree.incompleteRequests() + " of its request trees folded methods into Other nodes or dropped"
                    + " calls");
        }
        if (tree.amendedWithoutTree()) {
            reasons.add(
                    "a late fragment arrived after its request's tree was forgotten, so its counts are approximate");
        }
        if (tree.assemblyOnly()) {
            reasons.add("its handler only assembled its result, so work that ran later or elsewhere may be missing");
        }
        return new MethodRoutes.RouteEvidence(tree.requestsSeen(), !reasons.isEmpty(), reasons);
    }

    /**
     * A cheap fingerprint of what the route trees hold, which changes with every merged tree and claim generation, so
     * a cached projection that read them knows it is stale. 0 when the sensor does not record this run, or HTTP
     * Exchanges is not visible.
     */
    public long routeTreesFingerprint() {
        return routeTreesFingerprint(read());
    }

    /** {@link #routeTreesFingerprint()} under {@code read}, the read its caller's other reads are given. */
    public long routeTreesFingerprint(AgentEvidence.Read read) {
        if (shownReason(read) != null) {
            return 0L;
        }
        Run current = settledRun();
        if (current == null) {
            return read.key();
        }
        synchronized (lock) {
            return (current.generation * 1_000_003L + current.routes.version()) * 31 + current.clears * 7 + read.key();
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
            publish(current);
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
                current.routes.maxNodes(),
                current.routes.routes().stream()
                        .mapToLong(RouteTree::foldedCalls)
                        .sum());
    }

    private List<String> limitations(Run current, boolean assembly) {
        List<String> limitations = new ArrayList<>(List.of(LIMITATION_SCOPE, LIMITATION_CALLS, LIMITATION_RUN));
        if (assembly) {
            limitations.add(LIMITATION_ASSEMBLY);
        }
        if (current.clears > 0) {
            limitations.add(RECORDING_CLEARED);
        }
        long unrouted = current.routes.unrouted();
        if (unrouted > 0) {
            limitations.add(unrouted + (unrouted == 1 ? " request tree is" : " request trees are")
                    + " in no route tree: the journal no longer named its route, or it was BootUI's or no request's.");
        }
        if (current.routes.routesDropped() > 0) {
            limitations.add(current.routes.routesDropped() + " request trees of new routes were left out: the run"
                    + " already holds " + current.routes.maxRoutes() + " routes or its node budget is spent.");
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
                map.put("treesWaitingForExchange", current.store.unresolvedCount());
                map.put("treesNamedLate", current.store.lateNamed());
                map.put("treesNeverNamed", current.store.neverNamed());
                map.put("keptNodes", current.store.keptNodes());
                map.put("clearedTrees", current.store.clearedTrees());
                map.put("clearedFragments", current.store.clearedFragments());
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

    /** Said once the recording was cleared (M5-11). */
    static final String RECORDING_CLEARED = "The recording was cleared: request and route trees recorded before then"
            + " were dropped, and so is a request that had a fragment flushed before the clear, never kept partial.";

    /** Route trees bounded by the agent evidence bound (M5-11). */
    private RouteTrees newRouteTrees() {
        return new RouteTrees(evidence.scaled(RouteTrees.MAX_NODES, 1_000), evidence.scaled(RouteTrees.MAX_ROUTES, 10));
    }

    /** Publishes what {@code current} holds, for the evidence store's usage, which never waits on the lock. */
    private void publish(Run current) {
        usageBytes = current.store.estimatedBytes() + current.routes.estimatedBytes();
        usageMaxBytes = current.store.maxEstimatedBytes() + current.routes.maxEstimatedBytes();
        usageTrees = current.store.trees();
        usageRoutes = current.routes.routeCount();
        usageRouteNodes = current.routes.nodes();
        // About 100 bytes a method key: they name code, not requests, so they are kept through a clear.
        usageIndexBytes = (long) current.keys.size() * 100;
    }

    /** This service's trees as a store of the agent evidence contract: counted, and cleared with the journal. */
    private final class Store implements AgentEvidence.Store {

        @Override
        public String id() {
            return "code-paths";
        }

        @Override
        public String panel() {
            return BootUiPanels.CODE_PATHS;
        }

        @Override
        public String title() {
            return "Code Paths";
        }

        @Override
        public String unavailableReason() {
            return CodePathsService.this.unavailableReason();
        }

        @Override
        public AgentEvidence.Usage usage() {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("requestTrees", usageTrees);
            counts.put("routes", usageRoutes);
            counts.put("routeNodes", usageRouteNodes);
            counts.put("indexBytes", usageIndexBytes);
            return new AgentEvidence.Usage(usageBytes, usageMaxBytes, counts);
        }

        /**
         * Drops every request and route tree of the run, under the lock the drain thread takes for each fragment, so
         * none is merged half before and half after; from now on, a fragment flushed before the clear is dropped with
         * its request. The adaptive exclusions, the method keys, and the counts since the claim are kept.
         */
        @Override
        public String clear(long epochMillis) {
            Run current;
            synchronized (lock) {
                current = run;
                if (current == null) {
                    return null;
                }
                int trees = current.store.clear(nanoTime.getAsLong());
                int routes = current.routes.routeCount();
                current.routes = current.routes.cleared();
                current.clears++;
                // Drops before the clear no longer bear on what the route trees hold.
                current.droppedAtStart = current.agentDropped();
                publish(current);
                if (trees == 0 && routes == 0) {
                    return null;
                }
                return trees + (trees == 1 ? " request tree" : " request trees") + " and " + routes
                        + (routes == 1 ? " route tree" : " route trees") + " of Code Paths";
            }
        }
    }

    /** One run: its claim, drainer route, store, and exclusion. */
    private final class Run implements Consumer<long[]> {

        final AgentClaim claim;
        final long generation;
        final AgentRecordDrainer drainer;
        final RequestTreeStore store;
        final AdaptiveExclusion exclusion = new AdaptiveExclusion();
        RouteTrees routes = newRouteTrees();
        final Map<Integer, String> keys = new HashMap<>();
        long droppedAtStart;
        long clears;
        long fragments;
        long stale;
        long malformed;
        private volatile Set<String> repositories;

        Run(AgentClaim claim) {
            this.claim = claim;
            this.generation = claim.generation();
            this.drainer = claim.drainer();
            // Reads the outcomes installed now, incrementally where the reader can (M52-05).
            this.store = new RequestTreeStore(
                    new RequestOutcomeReader() {
                        @Override
                        public Map<String, RequestOutcome> apply(Set<String> ids) {
                            return outcomes.apply(ids);
                        }

                        @Override
                        public long watermark() {
                            return outcomes.watermark();
                        }

                        @Override
                        public Exchanges exchangesAfter(Set<String> ids, long after) {
                            return outcomes.exchangesAfter(ids, after);
                        }
                    },
                    // Shrunk in proportion by a configured agent evidence bound (M5-11).
                    evidence.scaled(RequestTreeStore.MAX_OPEN, 16),
                    evidence.scaled(RequestTreeStore.MAX_KEPT_NODES, RequestTreeBuilder.MAX_NODES));
            this.store.onSettled((tree, outcome) ->
                    routes.add(tree, outcome.route(), tree.requestId() != null && isAssemblyOnly(tree.requestId())));
            // A fragment arriving after its tree was merged amends its route's executed methods (M5-7a).
            // Reads the field each time: a clear replaces the route trees.
            this.store.onAmended((route, before, added, incomplete) -> routes.amend(route, before, added, incomplete));
            this.droppedAtStart = agentDropped();
        }

        /** The code-paths fragments the agent dropped in this JVM, its pool or queue full; 0 when unknown. */
        long agentDropped() {
            try {
                Map<String, Object> counters = AgentBridgeAccess.map(access.status(), AgentSensorSettings.CODE_PATHS);
                Long pool = AgentBridgeAccess.number(counters, "fragmentsDropped");
                Long queue = AgentBridgeAccess.number(counters, "queueDropped");
                return (pool == null ? 0L : pool) + (queue == null ? 0L : queue);
            } catch (RuntimeException ex) {
                return 0L;
            }
        }

        /** The classes of the application's repository beans, read once per run from the Beans panel's beans. */
        Set<String> repositories() {
            Set<String> known = repositories;
            if (known != null) {
                return known;
            }
            Set<String> classes = new HashSet<>();
            try {
                StructureSnapshot snapshot = structure.get();
                if (snapshot != null) {
                    for (StructureSnapshot.Bean bean : snapshot.beans()) {
                        String type = BeanInvocations.userClass(bean.type());
                        if (bean.repository() && type != null) {
                            classes.add(type);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                // Names alone tell a repository then.
            }
            repositories = Set.copyOf(classes);
            return repositories;
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
                publish(this);
            }
            for (int id : newly) {
                claim.excludeCodePathsMethod(id);
            }
        }
    }
}
