package io.github.jdubois.bootui.engine.codepaths;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One route's call tree in this run ({@code docs/PLAN-v2.md} §5.14, M5-4b): its warm requests' {@link RequestTree}s
 * merged by {@code (parent, method, phase)}, with per node the requests that reached it, its calls, total and self time, and a
 * compact histogram of the time each request spent in it ({@value #BUCKETS} log2 buckets of microseconds, with the
 * node's minimum and maximum, so its percentiles are approximate). The route's first recorded request, the first whose
 * tree settled, is kept apart, of which only its start, length, and request id are kept, much as
 * {@code route-time-breakdown} reports a route's first request as cold; a request whose handler only assembled its
 * result is counted as {@linkplain #assemblyRequests() assembly only}, and the route with it.
 *
 * <p>At most {@link RouteTrees#maxNodesPerRoute()} nodes: regular nodes stop {@value #OTHER_RESERVE} short of it, and
 * also when the {@linkplain RouteTrees global budget} is spent, after which a parent's further methods go to its one
 * Other node, created while the reserve lasts; a request node that fits nowhere leaves its time in its parent's self
 * time, so the self times of a request's own nodes always add up to its own time. Asynchronous children stay under one
 * {@link RequestTree#ASYNC} node per parent, never subtracted from it, under the method that submitted the work when
 * its requests' trees say so. Each node also sums the SQL, REST client, cache, and AI calls stamped to it (M5-4c), and
 * its {@linkplain #ownNanos own time} is its self time minus theirs; a request node whose time stays in its parent leaves
 * its calls there too. Not thread-safe: owned by {@link RouteTrees}.</p>
 */
public final class RouteTree {

    /** Histogram buckets per node. */
    public static final int BUCKETS = 32;

    /** Nodes kept for Other nodes. */
    public static final int OTHER_RESERVE = 64;

    /** The warm request durations kept for the route's exact percentiles. */
    public static final int DURATIONS = 256;

    /** A request node whose time stays in its parent's self time. */
    private static final int FOLDED = -1;

    /** A request node inside an Other node or a folded one, whose time its ancestor already holds. */
    private static final int INSIDE = -2;

    private final String route;
    private final RouteTrees owner;
    private int count;
    private int[] parent = new int[16];
    private int[] method = new int[16];
    private byte[] phase = new byte[16];
    private int[] depth = new int[16];
    private boolean[] async = new boolean[16];
    private long[] requests = new long[16];
    private long[] calls = new long[16];
    private long[] total = new long[16];
    private long[] self = new long[16];
    private int[] histogram = new int[16 * BUCKETS];
    private long[] min = new long[16];
    private long[] max = new long[16];
    /** Per node with stamped calls: their calls, then their time, per kind; only such nodes, so most cost nothing. */
    private final Map<Integer, long[]> io = new HashMap<>();

    private final Map<Long, Integer> index = new HashMap<>();
    private long stampedCalls;
    private UnplacedCalls unplaced = UnplacedCalls.NONE;
    /** Stamped calls of a request node whose own node found no room here, as an executor's work folded away. */
    private long homelessCalls;
    /**
     * The first recorded request's method-to-method calls, by {@code (caller, callee)} method ids, which the warm tree
     * leaves out: Beans at runtime still counts them (M5-4c). At most one per request-tree node.
     */
    private final Map<Long, long[]> firstPairs = new HashMap<>();

    /**
     * Per method id, the requests of this route whose own tree executed it, the first recorded included, read from each
     * request tree before this tree folds any of its nodes, and amended by late fragments (M5-7a).
     */
    private final MethodCounts executed = new MethodCounts();

    private int methodStamp;
    private boolean methodsPartial;
    private long incompleteRequests;
    private long amendments;
    private boolean amendedWithoutTree;

    private long warm;
    private long assemblyRequests;
    private long ownNanos;
    private long asyncNanos;
    private final long[] durations = new long[DURATIONS];
    private long durationCount;
    private boolean first;
    private long firstStartNanos;
    private long firstDurationNanos;
    private String firstRequestId;
    private boolean firstAssembly;
    private long folded;
    private long[] scratch = new long[16];
    private int[] seen = new int[16];
    private int stamp;

    RouteTree(String route, RouteTrees owner) {
        this.route = route;
        this.owner = owner;
        add(-1, RequestTree.REQUEST, 0, false, (byte) 0);
    }

    /**
     * Merges one settled request tree of this route; the first recorded is kept apart, as its start, length, and request
     * id, since trees settle in about the order their requests ended, not started.
     */
    void offer(RequestTree tree, boolean assemblyOnly) {
        countMethods(tree);
        if (!first) {
            first = true;
            firstStartNanos = tree.startNanos();
            firstDurationNanos = tree.durationNanos();
            firstRequestId = tree.requestId();
            firstAssembly = assemblyOnly;
            rememberPairs(tree);
            return;
        }
        merge(tree, assemblyOnly);
    }

    private void merge(RequestTree tree, boolean assemblyOnly) {
        warm++;
        if (assemblyOnly) {
            assemblyRequests++;
        }
        ownNanos += tree.durationNanos();
        asyncNanos += tree.asyncNanos();
        durations[(int) (durationCount++ % DURATIONS)] = tree.durationNanos();
        int nodes = tree.nodeCount();
        int[] mapped = new int[nodes];
        int[] holder = new int[nodes];
        int[] touched = new int[nodes];
        int touchedCount = 0;
        stamp++;
        ensureScratch();
        mapped[0] = 0;
        scratch[0] = tree.total()[0];
        seen[0] = stamp;
        touched[touchedCount++] = 0;
        requestsAndTime(0, 1, tree.total()[0], tree.selfNanos(0));
        for (int node = 1; node < nodes; node++) {
            int requestParent = tree.parent()[node];
            int target = requestParent < 0 ? 0 : mapped[requestParent];
            if (target < 0 || method[target] == RequestTree.OTHER) {
                mapped[node] = INSIDE;
                holder[node] = target < 0 ? holder[requestParent] : target;
                continue;
            }
            int id = tree.method()[node];
            int merged = node(target, id, tree.phase()[node]);
            if (merged < 0) {
                if (id != RequestTree.ASYNC) {
                    // An asynchronous child's time is never the request's own.
                    self[target] += tree.total()[node];
                }
                folded += tree.calls()[node];
                mapped[node] = FOLDED;
                // An asynchronous child's calls are not its parent's either.
                holder[node] = id == RequestTree.ASYNC ? -1 : target;
                continue;
            }
            mapped[node] = merged;
            holder[node] = merged;
            boolean whole = method[merged] == RequestTree.OTHER;
            requestsAndTime(
                    merged, tree.calls()[node], tree.total()[node], whole ? tree.total()[node] : tree.selfNanos(node));
            ensureScratch();
            if (seen[merged] != stamp) {
                seen[merged] = stamp;
                scratch[merged] = 0L;
                touched[touchedCount++] = merged;
            }
            scratch[merged] += tree.total()[node];
        }
        for (int i = 0; i < tree.ioNodes().length; i++) {
            int node = tree.ioNodes()[i];
            int at = holder[node];
            for (int kind = 0; kind < CodePathStamps.KINDS; kind++) {
                long nodeCalls = tree.ioCalls()[i * CodePathStamps.KINDS + kind];
                if (nodeCalls == 0) {
                    continue;
                }
                stampedCalls += nodeCalls;
                if (at < 0) {
                    homelessCalls += nodeCalls;
                    continue;
                }
                long[] counts = io.computeIfAbsent(at, ignored -> new long[2 * CodePathStamps.KINDS]);
                counts[kind] += nodeCalls;
                counts[CodePathStamps.KINDS + kind] += tree.ioNanos()[i * CodePathStamps.KINDS + kind];
            }
        }
        unplaced = unplaced.plus(tree.unplaced());
        stampedCalls += tree.unplacedCalls();
        for (int i = 0; i < touchedCount; i++) {
            int node = touched[i];
            long time = scratch[node];
            requests[node]++;
            histogram[node * BUCKETS + bucket(time)]++;
            min[node] = Math.min(min[node], time);
            max[node] = Math.max(max[node], time);
        }
    }

    /**
     * Counts the methods one request's tree executed, once each, executor work included; a tree with an Other node or
     * calls recorded in no node may have executed methods it does not name, so it counts as incomplete.
     */
    private void countMethods(RequestTree tree) {
        int stamp = ++methodStamp;
        boolean incomplete = tree.droppedCalls() > 0;
        int[] methods = tree.method();
        for (int node = 1; node < tree.nodeCount(); node++) {
            int id = methods[node];
            if (id == RequestTree.OTHER) {
                incomplete = true;
            } else if (id >= 0) {
                countMethod(id, stamp);
            }
        }
        if (incomplete) {
            incompleteRequests++;
        }
    }

    private void countMethod(int id, int stamp) {
        if (!executed.contains(id) && (executed.size() >= owner.maxMethodsPerRoute() || !owner.reserveMethod())) {
            methodsPartial = true;
            return;
        }
        executed.count(id, stamp, true);
    }

    /**
     * Amends this route's executed methods with a fragment that arrived after its request's tree was merged here: each
     * method of {@code added} that {@code before}, the request's tree before the fragment, did not execute counts one
     * more request. With {@code before} {@code null}, the request's tree is no longer kept, so only methods this route
     * never executed are added, once each, and the counts become approximate.
     */
    void amend(int[] before, int[] added, boolean becameIncomplete) {
        int stamp = ++methodStamp;
        amendments++;
        MethodCounts known = null;
        if (before == null) {
            amendedWithoutTree = true;
        } else {
            known = new MethodCounts();
            for (int id : before) {
                if (id >= 0) {
                    known.count(id, 0, true);
                }
            }
        }
        for (int id : added) {
            if (id < 0 || (known != null ? known.contains(id) : executed.contains(id))) {
                continue;
            }
            countMethod(id, stamp);
        }
        if (becameIncomplete) {
            incompleteRequests++;
        }
    }

    /** The first request's calls from one method to another, outside work an executor ran. */
    private void rememberPairs(RequestTree tree) {
        for (int node = 1; node < tree.nodeCount(); node++) {
            int callee = tree.method()[node];
            int caller = tree.method()[tree.parent()[node]];
            if (callee < 0 || caller < 0 || tree.calls()[node] == 0) {
                continue;
            }
            firstPairs.computeIfAbsent(((long) caller << 32) | (callee & 0xFFFFFFFFL), ignored -> new long[1])[0] +=
                    tree.calls()[node];
        }
    }

    /**
     * The first recorded request's calls from one method to another, outside work an executor ran: per {@code (caller,
     * callee)} method ids, packed as {@code caller << 32 | callee}, its calls.
     */
    public Map<Long, Long> firstRequestPairs() {
        Map<Long, Long> pairs = new HashMap<>();
        firstPairs.forEach((pair, calls) -> pairs.put(pair, calls[0]));
        return pairs;
    }

    private void requestsAndTime(int node, long nodeCalls, long nodeTotal, long nodeSelf) {
        calls[node] += nodeCalls;
        total[node] += nodeTotal;
        self[node] += Math.max(0L, nodeSelf);
    }

    private void ensureScratch() {
        if (scratch.length < parent.length) {
            scratch = Arrays.copyOf(scratch, parent.length);
            seen = Arrays.copyOf(seen, parent.length);
        }
    }

    /**
     * The node of {@code (target, id, phase)}: an existing one, a new one while regular nodes remain, else
     * {@code target}'s Other node of that phase, created while the reserve lasts; -1 when none can be had. The phase is
     * part of a node's identity, so the time a method spends in the handler never lands on its response-write node,
     * whichever request arrived first; asynchronous work has no phase.
     */
    private int node(int target, int id, byte nodePhase) {
        boolean nodeAsync = async[target] || id == RequestTree.ASYNC;
        byte effective = nodeAsync ? 0 : nodePhase;
        Integer known = index.get(key(target, id, effective));
        if (known != null) {
            return known;
        }
        if (id != RequestTree.OTHER && count < owner.maxNodesPerRoute() - OTHER_RESERVE && owner.reserveRegular()) {
            int node = add(target, id, depth[target] + 1, nodeAsync, effective);
            index.put(key(target, id, effective), node);
            return node;
        }
        if (id == RequestTree.ASYNC) {
            // An asynchronous child never merges into an Other node of its parent's own time.
            return -1;
        }
        byte otherPhase = async[target] ? 0 : nodePhase;
        Integer other = index.get(key(target, RequestTree.OTHER, otherPhase));
        if (other != null) {
            return other;
        }
        if (count < owner.maxNodesPerRoute() && owner.reserveOther()) {
            int node = add(target, RequestTree.OTHER, depth[target] + 1, async[target], otherPhase);
            index.put(key(target, RequestTree.OTHER, otherPhase), node);
            return node;
        }
        return -1;
    }

    private static long key(int target, int id, int nodePhase) {
        return ((long) target << 34) | ((id & 0xFFFFFFFFL) << 2) | (nodePhase & 3);
    }

    private int add(int nodeParent, int id, int nodeDepth, boolean nodeAsync, byte nodePhase) {
        if (count == parent.length) {
            int capacity = count * 2;
            parent = Arrays.copyOf(parent, capacity);
            method = Arrays.copyOf(method, capacity);
            phase = Arrays.copyOf(phase, capacity);
            depth = Arrays.copyOf(depth, capacity);
            async = Arrays.copyOf(async, capacity);
            requests = Arrays.copyOf(requests, capacity);
            calls = Arrays.copyOf(calls, capacity);
            total = Arrays.copyOf(total, capacity);
            self = Arrays.copyOf(self, capacity);
            histogram = Arrays.copyOf(histogram, capacity * BUCKETS);
            min = Arrays.copyOf(min, capacity);
            max = Arrays.copyOf(max, capacity);
        }
        int node = count++;
        parent[node] = nodeParent;
        method[node] = id;
        phase[node] = nodePhase;
        depth[node] = nodeDepth;
        async[node] = nodeAsync;
        min[node] = Long.MAX_VALUE;
        max[node] = 0L;
        return node;
    }

    /** The histogram bucket of {@code nanos}: 0 under a microsecond, else 1 + floor(log2(microseconds)), at most 31. */
    static int bucket(long nanos) {
        long micros = Math.max(0L, nanos) / 1_000L;
        if (micros == 0L) {
            return 0;
        }
        return Math.min(BUCKETS - 1, 64 - Long.numberOfLeadingZeros(micros));
    }

    /** The lower bound of {@code bucket}, in nanoseconds. */
    static long lowerNanos(int bucket) {
        return bucket == 0 ? 0L : (1L << (bucket - 1)) * 1_000L;
    }

    /** The upper bound of {@code bucket}, in nanoseconds. */
    static long upperNanos(int bucket) {
        return (1L << bucket) * 1_000L;
    }

    // --- reads -------------------------------------------------------------------------------------------------------

    public String route() {
        return route;
    }

    /** The node count, the request's included. */
    public int nodeCount() {
        return count;
    }

    /** Warm requests merged. */
    public long warmRequests() {
        return warm;
    }

    /**
     * Warm requests whose handler only assembled its result: it ran on an event loop, returned a reactive or
     * asynchronous result, or BootUI could not tell where its work ran.
     */
    public long assemblyRequests() {
        return assemblyRequests;
    }

    /**
     * Whether any merged request, or the first recorded one while it is alone, was assembly only: its tree times
     * assembly, not execution.
     */
    public boolean assemblyOnly() {
        return assemblyRequests > 0 || (warm == 0 && first && firstAssembly);
    }

    /** Whether the route's first recorded request, kept apart, is known. */
    public boolean hasFirstRequest() {
        return first;
    }

    /** The route's first recorded request's start, on the monotonic clock; -1 when none. */
    public long firstRequestStartNanos() {
        return first ? firstStartNanos : -1L;
    }

    /** The route's first recorded request's own time; -1 when none. */
    public long firstRequestNanos() {
        return first ? firstDurationNanos : -1L;
    }

    /** The route's first recorded request's id, or {@code null}. */
    public String firstRequestId() {
        return firstRequestId;
    }

    /** The warm requests' own time, summed. */
    public long ownNanos() {
        return ownNanos;
    }

    /** The warm requests' asynchronous children's time, summed. */
    public long asyncNanos() {
        return asyncNanos;
    }

    /** Calls whose time stayed in their parent because no node could be had. */
    public long foldedCalls() {
        return folded;
    }

    /** The most recent warm requests' own times, at most {@value #DURATIONS}, unordered. */
    public long[] recentDurations() {
        return Arrays.copyOf(durations, (int) Math.min(durationCount, DURATIONS));
    }

    public int parent(int node) {
        return parent[node];
    }

    /** The node's method id, or {@link RequestTree#REQUEST}, {@link RequestTree#ASYNC}, or {@link RequestTree#OTHER}. */
    public int method(int node) {
        return method[node];
    }

    /** The node's request phase: 0 unknown, 1 filters, 2 handler, 3 response. */
    public int phase(int node) {
        return phase[node];
    }

    public int depth(int node) {
        return depth[node];
    }

    /** Whether the node is an asynchronous child or under one. */
    public boolean async(int node) {
        return async[node];
    }

    /** The warm requests that reached the node. */
    public long requests(int node) {
        return requests[node];
    }

    public long calls(int node) {
        return calls[node];
    }

    public long totalNanos(int node) {
        return total[node];
    }

    public long selfNanos(int node) {
        return self[node];
    }

    /** The node's histogram of per-request time: a copy. */
    public int[] histogram(int node) {
        return Arrays.copyOfRange(histogram, node * BUCKETS, node * BUCKETS + BUCKETS);
    }

    /** The least per-request time in the node among the requests that reached it; -1 when none did. */
    public long minNanos(int node) {
        return requests[node] == 0 ? -1L : min[node];
    }

    /** The most per-request time in the node among the requests that reached it; -1 when none did. */
    public long maxNanos(int node) {
        return requests[node] == 0 ? -1L : max[node];
    }

    /**
     * The per-request time in the node at quantile {@code q} (0 to 1) among the requests that reached it, approximate:
     * interpolated within its log2 bucket, whose bounds are clamped to the node's minimum and maximum, so a node every
     * request spent the same time in answers that time; -1 when none did.
     */
    public long percentileNanos(int node, double q) {
        long reached = 0;
        for (int b = 0; b < BUCKETS; b++) {
            reached += histogram[node * BUCKETS + b];
        }
        if (reached == 0) {
            return -1L;
        }
        double rank = Math.max(1.0, Math.ceil(q * reached));
        long seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            int inBucket = histogram[node * BUCKETS + b];
            if (inBucket > 0 && seen + inBucket >= rank) {
                double within = (rank - seen) / inBucket;
                long lower = Math.max(lowerNanos(b), min[node]);
                long upper = Math.max(lower, Math.min(upperNanos(b), max[node]));
                return lower + Math.round((upper - lower) * within);
            }
            seen += inBucket;
        }
        return max[node];
    }

    /** Whether the node is a method node of the request's own handler: in the handler phase and not asynchronous. */
    public boolean handlerNode(int node) {
        return node > 0 && !async[node] && phase[node] == 2 && method[node] != RequestTree.ASYNC;
    }

    /** The handler's time in application methods, summed over warm requests: the self times of its handler nodes. */
    public long handlerNanos() {
        long sum = 0;
        for (int node = 1; node < count; node++) {
            if (handlerNode(node)) {
                sum += self[node];
            }
        }
        return sum;
    }

    /**
     * The handler's own work in application methods, summed over warm requests: the {@linkplain #ownNanos own time} of
     * its handler nodes.
     */
    public long handlerOwnNanos() {
        long sum = 0;
        for (int node = 1; node < count; node++) {
            if (handlerNode(node)) {
                sum += ownNanos(node);
            }
        }
        return sum;
    }

    /** The calls of {@code kind} stamped to the node, summed over warm requests. */
    public long ioCalls(int node, int kind) {
        long[] counts = io.get(node);
        return counts == null ? 0L : counts[kind];
    }

    /** The time of the calls of {@code kind} stamped to the node, summed over warm requests. */
    public long ioNanos(int node, int kind) {
        long[] counts = io.get(node);
        return counts == null ? 0L : counts[CodePathStamps.KINDS + kind];
    }

    /** The time of every call stamped to the node, summed over warm requests. */
    public long ioNanos(int node) {
        long[] counts = io.get(node);
        if (counts == null) {
            return 0L;
        }
        long sum = 0;
        for (int kind = 0; kind < CodePathStamps.KINDS; kind++) {
            sum += counts[CodePathStamps.KINDS + kind];
        }
        return sum;
    }

    /**
     * The node's own work, summed over warm requests: its self time minus the recorded calls stamped to it, never
     * negative. Recorded calls without a stamp stay in it.
     */
    public long ownNanos(int node) {
        return Math.max(0L, self[node] - ioNanos(node));
    }

    /** The warm requests' recorded calls with a stamp. */
    public long stampedCalls() {
        return stampedCalls;
    }

    /**
     * The warm requests' recorded calls without a stamp, as recorded on another thread than the one that issued them:
     * a method that waited for them keeps their time in its own time.
     */
    public long unstampedCalls() {
        return unplaced.otherThread();
    }

    /** The time of {@link #unstampedCalls()}, unknown durations counted as 0. */
    public long unstampedNanos() {
        return unplaced.otherThreadNanos();
    }

    /** The warm requests' recorded calls no method node holds, by why. */
    public UnplacedCalls unplaced() {
        return unplaced;
    }

    /** Stamped calls whose request node found no room in the route's tree, as an executor's work folded away. */
    public long homelessCalls() {
        return homelessCalls;
    }

    /** Stamped calls no route-tree node holds: their fragment was missing, arrived late, or past the bound, or homeless. */
    public long unplacedCalls() {
        return unplaced.stampedUnplaced() + homelessCalls;
    }

    // --- executed methods (M5-7a) ------------------------------------------------------------------------------------

    /** The requests merged here, warm and first, each counted once. */
    public long requestsSeen() {
        return warm + (first ? 1 : 0);
    }

    /**
     * The requests of this route whose own tree executed method {@code id}, the first recorded included, executor work
     * the agent followed and late fragments included; 0 when none did, or when the route's method table was full.
     */
    public long executedRequests(int id) {
        return executed.count(id);
    }

    /** Every method id a request of this route executed, unordered: a copy. */
    public int[] executedMethods() {
        return executed.ids();
    }

    /** Whether some executed methods are missing from {@link #executedRequests}: its table or the run's was full. */
    public boolean methodsPartial() {
        return methodsPartial;
    }

    /** The requests whose tree had an Other node or calls in no node, so may have executed methods it does not name. */
    public long incompleteRequests() {
        return incompleteRequests;
    }

    /** The late fragments that amended this route's executed methods after their request's tree was merged. */
    public long amendments() {
        return amendments;
    }

    /** Whether a late fragment amended it after its request's tree was no longer kept: its counts are approximate. */
    public boolean amendedWithoutTree() {
        return amendedWithoutTree;
    }
}
