package io.github.jdubois.bootui.engine.codepaths;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges one request's code-paths fragments into its {@link RequestTree} ({@code docs/PLAN-v2.md} §5.14, M5-4a). A
 * fragment of the request's own threads merges under the request's node; a handoff's fragment merges under its child
 * execution's {@link RequestTree#ASYNC} node, kept apart and never subtracted from the request. Nodes merge by
 * {@code (parent, method)}, in fragment order, at most {@value #MAX_NODES} of them: regular nodes stop
 * {@value #OTHER_RESERVE} short of it, after which a parent's new methods go to its one Other node, created while the
 * reserve lasts, and calls under an Other node stay in its time; a node that fits nowhere, or would sit deeper than
 * {@value #MAX_DEPTH} levels below the request, leaves its time in its parent. The request's time is the union of its
 * threads' fragments, and an execution's the union of its own, so overlapping fragments count once. Not thread-safe.
 */
public final class RequestTreeBuilder {

    /** The most nodes in a request tree, the request's and the Other nodes included. */
    public static final int MAX_NODES = 512;

    /** Nodes kept for Other nodes. */
    public static final int OTHER_RESERVE = 32;

    /** The deepest level below the request. */
    public static final int MAX_DEPTH = 32;

    /** A fragment node whose time stays in its parent, its subtree with it. */
    private static final int FOLDED = -1;

    /** A fragment node inside an ancestor that already counts its whole time. */
    private static final int INSIDE = -2;

    private final String key;
    private final String requestId;
    private long generation = Long.MIN_VALUE;
    private long startMillis = Long.MAX_VALUE;
    private long startNanos = Long.MAX_VALUE;
    private int fragments;
    private long dropped;
    private boolean cut;

    private int count;
    private int[] parent = new int[16];
    private int[] method = new int[16];
    private int[] depth = new int[16];
    private long[] execution = new long[16];
    private int[] executionKind = new int[16];
    private byte[] phase = new byte[16];
    private long[] calls = new long[16];
    private long[] total = new long[16];
    private long[] child = new long[16];
    private final Map<Long, Integer> index = new HashMap<>();
    private final Map<Long, Integer> executions = new HashMap<>();
    private final Intervals own = new Intervals();
    private final Intervals async = new Intervals();
    private final Map<Integer, Intervals> perExecution = new LinkedHashMap<>();

    /**
     * @param key the request's key ({@link RequestTree#key})
     * @param requestId the request id, or {@code null}
     */
    public RequestTreeBuilder(String key, String requestId) {
        this.key = key;
        this.requestId = requestId;
        add(-1, RequestTree.REQUEST, 0, 0L, 0, 0);
    }

    /** The key of the request a fragment belongs to: its request id, else its execution id. */
    public static String keyOf(CodePathFragment fragment) {
        if (fragment.request() != 0L) {
            return CodePathFragment.hex(fragment.request());
        }
        return "exec-" + CodePathFragment.hex(fragment.execution());
    }

    /** The request id, or {@code null} for work only an execution owns. */
    public String requestId() {
        return requestId;
    }

    /** How many fragments were merged. */
    public int fragments() {
        return fragments;
    }

    /** How many nodes the tree has, the request's included. */
    public int nodeCount() {
        return count;
    }

    /** Merges {@code fragment}, which must belong to this request. */
    public void add(CodePathFragment fragment) {
        fragments++;
        generation = Math.max(generation, fragment.generation());
        startMillis = Math.min(startMillis, fragment.startMillis());
        startNanos = Math.min(startNanos, fragment.startNanos());
        dropped += fragment.dropped();
        cut |= (fragment.flags() & CodePathFragment.FLAG_CUT) != 0;
        int attach;
        if (fragment.handoff()) {
            attach = executionNode(fragment.execution(), fragment.executionKind());
            if (attach < 0) {
                // No room even for the execution: its calls are counted, its time is not the request's own.
                for (int node = 0; node < fragment.nodeCount(); node++) {
                    dropped += fragment.calls()[node];
                }
                return;
            }
            perExecution
                    .computeIfAbsent(attach, ignored -> new Intervals())
                    .add(fragment.startNanos(), fragment.endNanos());
            async.add(fragment.startNanos(), fragment.endNanos());
        } else {
            attach = 0;
            own.add(fragment.startNanos(), fragment.endNanos());
        }
        calls[attach]++;
        int[] mapped = new int[fragment.nodeCount()];
        for (int node = 0; node < fragment.nodeCount(); node++) {
            int fragmentParent = fragment.parent()[node];
            long nodeCalls = fragment.calls()[node];
            long nodeTotal = fragment.total()[node];
            if (fragmentParent >= 0 && mapped[fragmentParent] < 0) {
                mapped[node] = INSIDE;
                dropped += nodeCalls;
                continue;
            }
            int target = fragmentParent < 0 ? attach : mapped[fragmentParent];
            if (method[target] == RequestTree.OTHER) {
                mapped[node] = INSIDE;
                dropped += nodeCalls;
                continue;
            }
            int merged =
                    depth[target] + 1 > MAX_DEPTH ? -1 : node(target, fragment.method()[node], fragment.phase()[node]);
            if (merged < 0) {
                // Its time stays in its parent's own.
                if (fragmentParent >= 0) {
                    child[target] = Math.max(0L, child[target] - nodeTotal);
                }
                mapped[node] = FOLDED;
                dropped += nodeCalls;
                continue;
            }
            calls[merged] += nodeCalls;
            total[merged] += nodeTotal;
            if (method[merged] != RequestTree.OTHER) {
                child[merged] += fragment.child()[node];
            }
            if (phase[merged] == 0) {
                phase[merged] = fragment.phase()[node];
            }
            if (fragmentParent < 0) {
                child[attach] += nodeTotal;
            }
            mapped[node] = merged;
        }
    }

    /** The node of a child execution under the request, created while regular nodes remain; -1 when none can be. */
    private int executionNode(long id, int kind) {
        Integer known = executions.get(id);
        if (known != null) {
            return known;
        }
        if (count >= MAX_NODES - OTHER_RESERVE) {
            return -1;
        }
        int node = add(0, RequestTree.ASYNC, 1, id, kind, 0);
        executions.put(id, node);
        return node;
    }

    /**
     * The node of {@code (target, id)}: an existing one, a new one while regular nodes remain, else {@code target}'s
     * Other node, created while the reserve lasts; -1 when none can be had. A fragment's own Other node merges into
     * {@code target}'s Other node whenever one can be had.
     */
    private int node(int target, int id, int nodePhase) {
        Integer known = index.get(key(target, id));
        if (known != null) {
            return known;
        }
        if (id != RequestTree.OTHER && count < MAX_NODES - OTHER_RESERVE) {
            int node = add(target, id, depth[target] + 1, 0L, 0, nodePhase);
            index.put(key(target, id), node);
            return node;
        }
        Integer other = index.get(key(target, RequestTree.OTHER));
        if (other != null) {
            return other;
        }
        if (count < MAX_NODES) {
            int node = add(target, RequestTree.OTHER, depth[target] + 1, 0L, 0, nodePhase);
            index.put(key(target, RequestTree.OTHER), node);
            return node;
        }
        return -1;
    }

    private static long key(int target, int id) {
        return ((long) target << 32) | (id & 0xFFFFFFFFL);
    }

    private int add(int nodeParent, int id, int nodeDepth, long executionId, int kind, int nodePhase) {
        if (count == parent.length) {
            int capacity = Math.min(MAX_NODES, count * 2);
            parent = Arrays.copyOf(parent, capacity);
            method = Arrays.copyOf(method, capacity);
            depth = Arrays.copyOf(depth, capacity);
            execution = Arrays.copyOf(execution, capacity);
            executionKind = Arrays.copyOf(executionKind, capacity);
            phase = Arrays.copyOf(phase, capacity);
            calls = Arrays.copyOf(calls, capacity);
            total = Arrays.copyOf(total, capacity);
            child = Arrays.copyOf(child, capacity);
        }
        int node = count++;
        parent[node] = nodeParent;
        method[node] = id;
        depth[node] = nodeDepth;
        execution[node] = executionId;
        executionKind[node] = kind;
        phase[node] = (byte) nodePhase;
        return node;
    }

    /**
     * What a settled tree keeps so a late fragment can still merge into it ({@link #resume}), once its builder, whose
     * maps and intervals cost more than its tree, is released: the disjoint spans of the request's own fragments, of
     * its asynchronous children, and of each child execution by node, with the children's time of the request's and
     * each execution's node before the tree capped it at their union.
     *
     * @param own the request's own spans, as {@link Intervals#compact}
     * @param async its asynchronous children's spans
     * @param executions each child execution's node and spans
     * @param children the request's and each child execution's node and its children's time, uncapped
     */
    public record Spans(long[] own, long[] async, Map<Integer, long[]> executions, Map<Integer, Long> children) {}

    /** The spans to keep beside the tree {@link #build()} returns. */
    public Spans spans() {
        Map<Integer, long[]> byExecution = new LinkedHashMap<>();
        Map<Integer, Long> children = new LinkedHashMap<>();
        children.put(0, child[0]);
        for (Map.Entry<Integer, Intervals> entry : perExecution.entrySet()) {
            byExecution.put(entry.getKey(), entry.getValue().compact());
            children.put(entry.getKey(), child[entry.getKey()]);
        }
        return new Spans(own.compact(), async.compact(), byExecution, children);
    }

    /**
     * A builder that goes on merging into {@code tree}, a tree this class built, with the {@code spans} kept beside it:
     * it merges as the builder that built the tree would have.
     */
    public static RequestTreeBuilder resume(RequestTree tree, Spans spans) {
        RequestTreeBuilder builder = new RequestTreeBuilder(tree.key(), tree.requestId());
        int nodes = tree.nodeCount();
        builder.count = nodes;
        int capacity = Math.max(16, nodes);
        builder.parent = Arrays.copyOf(tree.parent(), capacity);
        builder.method = Arrays.copyOf(tree.method(), capacity);
        builder.execution = Arrays.copyOf(tree.execution(), capacity);
        builder.executionKind = Arrays.copyOf(tree.executionKind(), capacity);
        builder.phase = Arrays.copyOf(tree.phase(), capacity);
        builder.calls = Arrays.copyOf(tree.calls(), capacity);
        builder.total = Arrays.copyOf(tree.total(), capacity);
        builder.child = Arrays.copyOf(tree.child(), capacity);
        builder.depth = new int[capacity];
        for (int node = 1; node < nodes; node++) {
            int nodeParent = builder.parent[node];
            builder.depth[node] = builder.depth[nodeParent] + 1;
            if (builder.method[node] == RequestTree.ASYNC) {
                builder.executions.put(builder.execution[node], node);
            } else {
                builder.index.put(key(nodeParent, builder.method[node]), node);
            }
        }
        builder.generation = tree.generation();
        builder.startMillis = tree.startMillis() == 0L ? Long.MAX_VALUE : tree.startMillis();
        builder.startNanos = tree.fragments() == 0 ? Long.MAX_VALUE : tree.startNanos();
        builder.fragments = tree.fragments();
        builder.dropped = tree.droppedCalls();
        builder.cut = tree.cut();
        if (spans != null) {
            builder.own.addAll(spans.own());
            builder.async.addAll(spans.async());
            for (Map.Entry<Integer, long[]> entry : spans.executions().entrySet()) {
                Intervals intervals = new Intervals();
                intervals.addAll(entry.getValue());
                builder.perExecution.put(entry.getKey(), intervals);
            }
            for (Map.Entry<Integer, Long> entry : spans.children().entrySet()) {
                if (entry.getKey() < nodes) {
                    builder.child[entry.getKey()] = entry.getValue();
                }
            }
        }
        return builder;
    }

    /** The request's tree as merged so far; the builder can go on merging. */
    public RequestTree build() {
        long[] totals = Arrays.copyOf(total, count);
        long[] children = Arrays.copyOf(child, count);
        totals[0] = own.union();
        for (Map.Entry<Integer, Intervals> entry : perExecution.entrySet()) {
            totals[entry.getKey()] = entry.getValue().union();
        }
        for (int node = 0; node < count; node++) {
            children[node] = Math.max(0L, Math.min(children[node], totals[node]));
        }
        return new RequestTree(
                key,
                requestId,
                generation,
                startMillis == Long.MAX_VALUE ? 0L : startMillis,
                startNanos == Long.MAX_VALUE ? 0L : startNanos,
                fragments,
                async.union(),
                dropped,
                cut,
                Arrays.copyOf(parent, count),
                Arrays.copyOf(method, count),
                Arrays.copyOf(execution, count),
                Arrays.copyOf(executionKind, count),
                Arrays.copyOf(phase, count),
                Arrays.copyOf(calls, count),
                totals,
                children);
    }
}
