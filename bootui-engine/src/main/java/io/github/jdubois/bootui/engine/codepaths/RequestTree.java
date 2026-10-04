package io.github.jdubois.bootui.engine.codepaths;

/**
 * One request's call tree of application bean methods ({@code docs/PLAN-v2.md} §5.14, M5-4a), merged from the code-paths
 * fragments its threads flushed. Node 0 is the request; its children are the top-level methods of the request's own
 * threads, merged by method and request phase, and one {@link #ASYNC} node per child execution an executor ran for it, whose children are
 * that execution's methods. A node's {@linkplain #selfNanos self time} is its total minus its children's, never
 * negative; the request's own time is the union of its threads' fragments, and asynchronous children are shown apart,
 * never subtracted from it. At most {@value RequestTreeBuilder#MAX_NODES} nodes and {@value RequestTreeBuilder#MAX_DEPTH}
 * levels below the request: past the budget, a parent's further methods go to its one {@link #OTHER} node, and calls
 * that fit in neither stay in their parent's time. A child execution's node sits under the method that submitted its
 * work when its fragment carries that method's stamp, else under the request. SQL, REST client, cache, and AI calls
 * recorded with a code-paths stamp are counted under the node that was innermost when they ran (M5-4c). Immutable.
 *
 * @param key the request's key: its 16-hexadecimal-digit request id, or {@code exec-} and the execution id for work
 *     only an execution owns
 * @param requestId the request id, or {@code null}
 * @param generation the claim generation of its fragments
 * @param startMillis when its first fragment started, in epoch milliseconds
 * @param startNanos when its first fragment started, on the JVM's nanosecond clock
 * @param fragments how many fragments were merged
 * @param asyncNanos the union of its asynchronous children's time
 * @param droppedCalls calls recorded in no node, by the agent or here
 * @param cut whether a fragment was flushed with calls still open, as an asynchronous handler's
 * @param parent each node's parent, -1 for the request
 * @param method each node's method id, or {@link #REQUEST}, {@link #ASYNC}, or {@link #OTHER}
 * @param execution each {@link #ASYNC} node's execution id's 64 bits, else 0
 * @param executionKind each {@link #ASYNC} node's execution kind ({@link CodePathFragment#EXECUTION_ASYNC} or
 *     {@link CodePathFragment#EXECUTION_TASK}), else 0
 * @param phase each node's request phase: 0 unknown, 1 filters, 2 handler, 3 response
 * @param calls each node's calls; for the request and an {@link #ASYNC} node, its fragments
 * @param total each node's total time in nanoseconds
 * @param child each node's children's time in nanoseconds, at most its total
 * @param ioNodes the nodes with recorded calls stamped to them, ascending: only those, so a tree without calls costs
 *     nothing more
 * @param ioCalls per such node and {@linkplain CodePathStamps call kind}, at {@code index * KINDS + kind}, the SQL,
 *     REST client, cache, and AI calls stamped to it: recorded while it was the innermost instrumented method open on
 *     their thread (M5-4c)
 * @param ioNanos the same calls' time, unknown durations counted as 0
 * @param unplaced the request's recorded calls no node holds, by why: without a stamp, issued outside every
 *     instrumented method, or stamped to a fragment the tree did not hold when they were attached
 */
public record RequestTree(
        String key,
        String requestId,
        long generation,
        long startMillis,
        long startNanos,
        int fragments,
        long asyncNanos,
        long droppedCalls,
        boolean cut,
        int[] parent,
        int[] method,
        long[] execution,
        int[] executionKind,
        byte[] phase,
        long[] calls,
        long[] total,
        long[] child,
        int[] ioNodes,
        long[] ioCalls,
        long[] ioNanos,
        UnplacedCalls unplaced) {

    public RequestTree {
        unplaced = unplaced == null ? UnplacedCalls.NONE : unplaced;
    }

    /** A tree without recorded calls. */
    public RequestTree(
            String key,
            String requestId,
            long generation,
            long startMillis,
            long startNanos,
            int fragments,
            long asyncNanos,
            long droppedCalls,
            boolean cut,
            int[] parent,
            int[] method,
            long[] execution,
            int[] executionKind,
            byte[] phase,
            long[] calls,
            long[] total,
            long[] child) {
        this(
                key,
                requestId,
                generation,
                startMillis,
                startNanos,
                fragments,
                asyncNanos,
                droppedCalls,
                cut,
                parent,
                method,
                execution,
                executionKind,
                phase,
                calls,
                total,
                child,
                new int[0],
                new long[0],
                new long[0],
                UnplacedCalls.NONE);
    }

    /** Its stamped calls no node holds: their fragment was missing, arrived late, or they were past the bound. */
    public long unplacedCalls() {
        return unplaced.stampedUnplaced();
    }

    /** Its recorded calls without a stamp, as recorded on another thread than the one that issued them. */
    public long unstampedCalls() {
        return unplaced.otherThread();
    }

    /** The method id of the request's node. */
    public static final int REQUEST = -3;

    /** The method id of a child execution's node. */
    public static final int ASYNC = -2;

    /** The method id of an Other node. */
    public static final int OTHER = CodePathFragment.OTHER;

    public int nodeCount() {
        return parent.length;
    }

    /** The request's own time: the union of its threads' fragments. */
    public long durationNanos() {
        return total[0];
    }

    /** A node's time outside its children, never negative. */
    public long selfNanos(int node) {
        return Math.max(0L, total[node] - Math.min(child[node], total[node]));
    }

    /** The calls of {@code kind} stamped to {@code node}. */
    public long ioCalls(int node, int kind) {
        int index = java.util.Arrays.binarySearch(ioNodes, node);
        return index < 0 ? 0L : ioCalls[index * CodePathStamps.KINDS + kind];
    }

    /** The time of the calls of {@code kind} stamped to {@code node}. */
    public long ioNanos(int node, int kind) {
        int index = java.util.Arrays.binarySearch(ioNodes, node);
        return index < 0 ? 0L : ioNanos[index * CodePathStamps.KINDS + kind];
    }

    /** The time of every call stamped to {@code node}. */
    public long ioNanos(int node) {
        int index = java.util.Arrays.binarySearch(ioNodes, node);
        if (index < 0) {
            return 0L;
        }
        long sum = 0;
        for (int kind = 0; kind < CodePathStamps.KINDS; kind++) {
            sum += ioNanos[index * CodePathStamps.KINDS + kind];
        }
        return sum;
    }

    /**
     * A node's own work: its self time minus the recorded calls stamped to it, never negative. Calls without a stamp
     * stay in it.
     */
    public long ownNanos(int node) {
        return Math.max(0L, selfNanos(node) - ioNanos(node));
    }
}
