package io.github.jdubois.bootui.engine.codepaths;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One route's call tree in this run ({@code docs/PLAN-v2.md} §5.14, M5-4b): its warm requests' {@link RequestTree}s
 * merged by {@code (parent, method)}, with per node the requests that reached it, its calls, total and self time, and a
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
 * {@link RequestTree#ASYNC} node per parent, never subtracted from it. Not thread-safe: owned by {@link RouteTrees}.</p>
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
    private final Map<Long, Integer> index = new HashMap<>();

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
        if (!first) {
            first = true;
            firstStartNanos = tree.startNanos();
            firstDurationNanos = tree.durationNanos();
            firstRequestId = tree.requestId();
            firstAssembly = assemblyOnly;
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
                continue;
            }
            mapped[node] = merged;
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
        for (int i = 0; i < touchedCount; i++) {
            int node = touched[i];
            long time = scratch[node];
            requests[node]++;
            histogram[node * BUCKETS + bucket(time)]++;
            min[node] = Math.min(min[node], time);
            max[node] = Math.max(max[node], time);
        }
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
     * The node of {@code (target, id)}: an existing one, a new one while regular nodes remain, else {@code target}'s
     * Other node, created while the reserve lasts; -1 when none can be had.
     */
    private int node(int target, int id, byte nodePhase) {
        Integer known = index.get(key(target, id));
        if (known != null) {
            return known;
        }
        boolean nodeAsync = async[target] || id == RequestTree.ASYNC;
        if (id != RequestTree.OTHER && count < owner.maxNodesPerRoute() - OTHER_RESERVE && owner.reserveRegular()) {
            int node = add(target, id, depth[target] + 1, nodeAsync, nodeAsync ? 0 : nodePhase);
            index.put(key(target, id), node);
            return node;
        }
        Integer other = index.get(key(target, RequestTree.OTHER));
        if (id == RequestTree.ASYNC) {
            // An asynchronous child never merges into an Other node of its parent's own time.
            return -1;
        }
        if (other != null) {
            return other;
        }
        if (count < owner.maxNodesPerRoute() && owner.reserveOther()) {
            int node = add(target, RequestTree.OTHER, depth[target] + 1, async[target], async[target] ? 0 : nodePhase);
            index.put(key(target, RequestTree.OTHER), node);
            return node;
        }
        return -1;
    }

    private static long key(int target, int id) {
        return ((long) target << 32) | (id & 0xFFFFFFFFL);
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
}
