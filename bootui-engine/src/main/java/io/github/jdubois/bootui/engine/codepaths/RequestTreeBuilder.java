package io.github.jdubois.bootui.engine.codepaths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges one request's code-paths fragments into its {@link RequestTree} ({@code docs/PLAN-v2.md} §5.14, M5-4a). A
 * fragment of the request's own threads merges under the request's node; a handoff's fragment merges under its child
 * execution's {@link RequestTree#ASYNC} node, kept apart and never subtracted from the request. Nodes merge by
 * {@code (parent, method, phase)}, in fragment order, at most {@value #MAX_NODES} of them: regular nodes stop
 * {@value #OTHER_RESERVE} short of it, after which a parent's new methods go to its one Other node, created while the
 * reserve lasts, and calls under an Other node stay in its time; a node that fits nowhere, or would sit deeper than
 * {@value #MAX_DEPTH} levels below the request, leaves its time in its parent. The request's time is the union of its
 * threads' fragments, and an execution's the union of its own, so overlapping fragments count once.
 *
 * <p>Stamps (M5-4c): each fragment's nodes are remembered by its sequence, as the request-tree node that holds each one's
 * time, so a recorded call's stamp names the exact node that issued it ({@link #attach}), and a handoff's fragment whose
 * submitter stamp names a node of the request goes under that node rather than the request: such a fragment waits until
 * its submitter's fragment arrives, and goes under the request if it never does ({@link #finish()}). Not
 * thread-safe.</p>
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
    private final Map<Long, int[]> holders = new HashMap<>();
    private final java.util.Set<Long> ownHolders = new java.util.LinkedHashSet<>();
    private final List<CodePathFragment> waiting = new ArrayList<>();
    /** Per node with stamped calls: its calls, then their time, per kind; only such nodes. */
    private final java.util.TreeMap<Integer, long[]> io = new java.util.TreeMap<>();

    private UnplacedCalls unplaced = UnplacedCalls.NONE;

    /**
     * By fragment sequence, the stamped calls attached while the tree did not hold their fragment, for at most
     * {@value #MAX_MISSING} sequences: one arriving later counts them as late rather than missing.
     */
    private final Map<Long, Long> missing = new LinkedHashMap<>();

    /** The most handoff fragments waiting for their submitter's fragment; past it, they go under the request. */
    static final int MAX_WAITING = 256;

    /** The most fragments whose nodes a builder remembers for stamps; a later one's stamps are unplaced. */
    static final int MAX_HOLDERS = 4_096;

    /** The most of the request's own fragments a settled tree keeps the nodes of, for late handoffs' submitters. */
    static final int MAX_KEPT_HOLDERS = 64;

    /** The most fragment sequences whose missing stamped calls are remembered, to tell a late one. */
    static final int MAX_MISSING = 64;

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

    /**
     * Merges {@code fragment}, which must belong to this request. A handoff's fragment whose submitter's fragment has not
     * arrived yet waits for it.
     */
    public void add(CodePathFragment fragment) {
        if (fragment.handoff()
                && fragment.submitter() != 0L
                && !executions.containsKey(fragment.execution())
                && !holders.containsKey(CodePathStamps.sequence(fragment.submitter()))
                && waiting.size() < MAX_WAITING) {
            waiting.add(fragment);
            return;
        }
        merge(fragment);
        release(fragment.sequence());
    }

    /** Merges the waiting fragments whose submitter's fragment {@code sequence} just merged, and theirs in turn. */
    private void release(long sequence) {
        if (waiting.isEmpty() || sequence == 0L) {
            return;
        }
        List<Long> merged = new ArrayList<>();
        merged.add(sequence);
        for (int i = 0; i < merged.size(); i++) {
            long arrived = merged.get(i);
            for (int w = 0; w < waiting.size(); ) {
                CodePathFragment fragment = waiting.get(w);
                if (CodePathStamps.sequence(fragment.submitter()) == arrived) {
                    waiting.remove(w);
                    merge(fragment);
                    if (fragment.sequence() != 0L) {
                        merged.add(fragment.sequence());
                    }
                } else {
                    w++;
                }
            }
        }
    }

    /** Merges the fragments still waiting for a submitter that never arrived, under the request. */
    private void mergeWaiting() {
        while (!waiting.isEmpty()) {
            CodePathFragment fragment = waiting.remove(0);
            merge(fragment);
            release(fragment.sequence());
        }
    }

    private void merge(CodePathFragment fragment) {
        fragments++;
        generation = Math.max(generation, fragment.generation());
        startMillis = Math.min(startMillis, fragment.startMillis());
        startNanos = Math.min(startNanos, fragment.startNanos());
        dropped += fragment.dropped();
        cut |= (fragment.flags() & CodePathFragment.FLAG_CUT) != 0;
        int attach;
        if (fragment.handoff()) {
            attach = executionNode(fragment.execution(), fragment.executionKind(), submitterNode(fragment));
            if (attach < 0) {
                // No room even for the execution: its calls are counted, its time is not the request's own.
                for (int node = 0; node < fragment.nodeCount(); node++) {
                    dropped += fragment.calls()[node];
                }
                int[] nowhere = new int[fragment.nodeCount()];
                Arrays.fill(nowhere, -1);
                remember(fragment, nowhere);
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
        int[] holder = new int[fragment.nodeCount()];
        for (int node = 0; node < fragment.nodeCount(); node++) {
            int fragmentParent = fragment.parent()[node];
            long nodeCalls = fragment.calls()[node];
            long nodeTotal = fragment.total()[node];
            if (fragmentParent >= 0 && mapped[fragmentParent] < 0) {
                mapped[node] = INSIDE;
                holder[node] = holder[fragmentParent];
                dropped += nodeCalls;
                continue;
            }
            int target = fragmentParent < 0 ? attach : mapped[fragmentParent];
            if (method[target] == RequestTree.OTHER) {
                mapped[node] = INSIDE;
                holder[node] = target;
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
                holder[node] = target;
                dropped += nodeCalls;
                continue;
            }
            calls[merged] += nodeCalls;
            total[merged] += nodeTotal;
            if (method[merged] != RequestTree.OTHER) {
                child[merged] += fragment.child()[node];
            }
            if (fragmentParent < 0) {
                child[attach] += nodeTotal;
            }
            mapped[node] = merged;
            holder[node] = merged;
        }
        remember(fragment, holder);
    }

    /**
     * Remembers, by the fragment's sequence, the request-tree node holding each of its nodes' time; stamped calls that
     * named this fragment before it arrived, after the tree settled, count as late.
     */
    private void remember(CodePathFragment fragment, int[] holder) {
        Long before = fragment.sequence() == 0L ? null : missing.remove(fragment.sequence());
        if (before != null) {
            unplaced = unplaced.arrivedLate(before);
        }
        if (fragment.sequence() != 0L && holders.size() < MAX_HOLDERS) {
            holders.put(fragment.sequence(), holder);
            if (!fragment.handoff() && ownHolders.size() < MAX_KEPT_HOLDERS) {
                ownHolders.add(fragment.sequence());
            }
        }
    }

    /**
     * The request-tree node a handoff's fragment goes under: the node its submitter stamp names, when the submitter's
     * fragment merged and the node leaves room for the execution's methods below it; else the request.
     */
    private int submitterNode(CodePathFragment fragment) {
        int node = holderOf(fragment.submitter());
        return node < 0 || depth[node] + 2 > MAX_DEPTH || method[node] == RequestTree.OTHER ? 0 : node;
    }

    /** The request-tree node holding the time of the fragment node {@code stamp} names, or -1 when none. */
    private int holderOf(long stamp) {
        if (stamp == 0L) {
            return -1;
        }
        int[] holder = holders.get(CodePathStamps.sequence(stamp));
        int node = CodePathStamps.node(stamp);
        if (holder == null || node >= holder.length) {
            return -1;
        }
        return holder[node];
    }

    /** Attaches {@code stamped} calls, and {@code unstamped} calls without a stamp, of unknown time. */
    public void attach(List<RequestOutcome.StampedCall> stamped, long unstamped) {
        attach(stamped, UnplacedCalls.otherThread(unstamped));
    }

    /**
     * Counts each stamped call under the node that issued it, and the request's calls no stamp places; a stamp whose
     * fragment the tree does not hold is counted as missing, and as late if that fragment arrives afterwards. Call once
     * the request's fragments merged.
     */
    public void attach(List<RequestOutcome.StampedCall> stamped, UnplacedCalls notPlaced) {
        mergeWaiting();
        unplaced = unplaced.plus(notPlaced);
        if (stamped == null) {
            return;
        }
        for (RequestOutcome.StampedCall call : stamped) {
            if (!CodePathStamps.placed(call.stamp())) {
                unplaced = unplaced.plus(
                        call.stamp() == 0L
                                ? new UnplacedCalls(1L, Math.max(0L, call.durationNanos()), 0L, 0L, 0L, 0L)
                                : new UnplacedCalls(0L, 0L, 1L, 0L, 0L, 0L));
                continue;
            }
            int node = holderOf(call.stamp());
            if (node < 0 || call.kind() < 0 || call.kind() >= CodePathStamps.KINDS) {
                unplaced = unplaced.withMissing(1L);
                long sequence = CodePathStamps.sequence(call.stamp());
                if (node < 0
                        && !holders.containsKey(sequence)
                        && (missing.containsKey(sequence) || missing.size() < MAX_MISSING)) {
                    missing.merge(sequence, 1L, Long::sum);
                }
                continue;
            }
            long[] counts = io.computeIfAbsent(node, ignored -> new long[2 * CodePathStamps.KINDS]);
            counts[call.kind()]++;
            counts[CodePathStamps.KINDS + call.kind()] += Math.max(0L, call.durationNanos());
        }
    }

    /**
     * The node of a child execution under {@code under}, the request or the method that submitted its work, created while
     * regular nodes remain; -1 when none can be.
     */
    private int executionNode(long id, int kind, int under) {
        Integer known = executions.get(id);
        if (known != null) {
            return known;
        }
        if (count >= MAX_NODES - OTHER_RESERVE) {
            return -1;
        }
        int node = add(under, RequestTree.ASYNC, depth[under] + 1, id, kind, 0);
        executions.put(id, node);
        return node;
    }

    /**
     * The node of {@code (target, id, nodePhase)}: an existing one, a new one while regular nodes remain, else
     * {@code target}'s Other node of that phase, created while the reserve lasts; -1 when none can be had. A fragment's
     * own Other node merges into {@code target}'s Other node whenever one can be had. The phase is part of a node's
     * identity, so a method entered in the handler and in the response write under one parent stays two nodes.
     */
    private int node(int target, int id, int nodePhase) {
        Integer known = index.get(key(target, id, nodePhase));
        if (known != null) {
            return known;
        }
        if (id != RequestTree.OTHER && count < MAX_NODES - OTHER_RESERVE) {
            int node = add(target, id, depth[target] + 1, 0L, 0, nodePhase);
            index.put(key(target, id, nodePhase), node);
            return node;
        }
        Integer other = index.get(key(target, RequestTree.OTHER, nodePhase));
        if (other != null) {
            return other;
        }
        if (count < MAX_NODES) {
            int node = add(target, RequestTree.OTHER, depth[target] + 1, 0L, 0, nodePhase);
            index.put(key(target, RequestTree.OTHER, nodePhase), node);
            return node;
        }
        return -1;
    }

    private static long key(int target, int id, int nodePhase) {
        return ((long) target << 34) | ((id & 0xFFFFFFFFL) << 2) | (nodePhase & 3);
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
     * @param holders by sequence, for at most {@value #MAX_KEPT_HOLDERS} of the request's own fragments, the node
     *     holding each of the fragment's nodes' time, so a late handoff's fragment still finds the node that submitted it
     * @param missing by sequence, for at most {@value #MAX_MISSING} fragments the tree did not hold, the stamped calls
     *     that named them, so one arriving late counts them as late
     */
    public record Spans(
            long[] own,
            long[] async,
            Map<Integer, long[]> executions,
            Map<Integer, Long> children,
            Map<Long, int[]> holders,
            Map<Long, Long> missing) {

        public Spans {
            holders = holders == null ? Map.of() : holders;
            missing = missing == null ? Map.of() : missing;
        }

        /** Spans without fragment sequences, as a tree recorded by an agent predating stamps. */
        public Spans(long[] own, long[] async, Map<Integer, long[]> executions, Map<Integer, Long> children) {
            this(own, async, executions, children, Map.of(), Map.of());
        }

        /** Spans without missing stamped calls. */
        public Spans(
                long[] own,
                long[] async,
                Map<Integer, long[]> executions,
                Map<Integer, Long> children,
                Map<Long, int[]> holders) {
            this(own, async, executions, children, holders, Map.of());
        }
    }

    /** The spans to keep beside the tree {@link #build()} returns. */
    public Spans spans() {
        Map<Integer, long[]> byExecution = new LinkedHashMap<>();
        Map<Integer, Long> children = new LinkedHashMap<>();
        children.put(0, child[0]);
        for (Map.Entry<Integer, Intervals> entry : perExecution.entrySet()) {
            byExecution.put(entry.getKey(), entry.getValue().compact());
            children.put(entry.getKey(), child[entry.getKey()]);
        }
        Map<Long, int[]> kept = new HashMap<>();
        for (Long sequence : ownHolders) {
            kept.put(sequence, holders.get(sequence));
        }
        return new Spans(own.compact(), async.compact(), byExecution, children, kept, new LinkedHashMap<>(missing));
    }

    /**
     * Every fragment's nodes this builder remembers by sequence, at most {@value #MAX_HOLDERS} fragments: the request's
     * own beyond the {@value #MAX_KEPT_HOLDERS} its spans keep, and its executors' fragments, which a tree waiting for
     * its exchange needs until its calls are attached ({@link #resume(RequestTree, Spans, Map)}).
     */
    public Map<Long, int[]> allHolders() {
        return new HashMap<>(holders);
    }

    /**
     * A builder as {@link #resume(RequestTree, Spans)} returns, which also knows {@code holders}, as
     * {@link #allHolders()} returned them, for the stamps of calls not attached yet.
     */
    public static RequestTreeBuilder resume(RequestTree tree, Spans spans, Map<Long, int[]> holders) {
        RequestTreeBuilder builder = resume(tree, spans);
        if (holders != null) {
            for (Map.Entry<Long, int[]> entry : holders.entrySet()) {
                if (builder.holders.size() >= MAX_HOLDERS) {
                    break;
                }
                builder.holders.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return builder;
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
        for (int i = 0; i < tree.ioNodes().length; i++) {
            long[] counts = new long[2 * CodePathStamps.KINDS];
            System.arraycopy(tree.ioCalls(), i * CodePathStamps.KINDS, counts, 0, CodePathStamps.KINDS);
            System.arraycopy(
                    tree.ioNanos(), i * CodePathStamps.KINDS, counts, CodePathStamps.KINDS, CodePathStamps.KINDS);
            builder.io.put(tree.ioNodes()[i], counts);
        }
        builder.unplaced = tree.unplaced();
        builder.depth = new int[capacity];
        for (int node = 1; node < nodes; node++) {
            int nodeParent = builder.parent[node];
            builder.depth[node] = builder.depth[nodeParent] + 1;
            if (builder.method[node] == RequestTree.ASYNC) {
                builder.executions.put(builder.execution[node], node);
            } else {
                builder.index.put(key(nodeParent, builder.method[node], builder.phase[node]), node);
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
            if (spans.holders() != null) {
                builder.holders.putAll(spans.holders());
                builder.ownHolders.addAll(spans.holders().keySet());
            }
            if (spans.missing() != null) {
                builder.missing.putAll(spans.missing());
            }
        }
        return builder;
    }

    private int[] ioNodes() {
        int[] nodes = new int[io.size()];
        int i = 0;
        for (int node : io.keySet()) {
            nodes[i++] = node;
        }
        return nodes;
    }

    /** The calls ({@code from} 0) or their time ({@code from} {@code KINDS}) of the nodes with stamped calls. */
    private long[] ioPart(int from) {
        long[] part = new long[io.size() * CodePathStamps.KINDS];
        int i = 0;
        for (long[] counts : io.values()) {
            System.arraycopy(counts, from, part, i++ * CodePathStamps.KINDS, CodePathStamps.KINDS);
        }
        return part;
    }

    /**
     * The request's tree once its fragments arrived: the handoff fragments still waiting for a submitter's fragment go
     * under the request first. The builder can go on merging.
     */
    public RequestTree finish() {
        mergeWaiting();
        return build();
    }

    /**
     * The request's tree as merged so far, without the handoff fragments still waiting for their submitter's fragment;
     * the builder can go on merging.
     */
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
                children,
                ioNodes(),
                ioPart(0),
                ioPart(CodePathStamps.KINDS),
                unplaced);
    }
}
