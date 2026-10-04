package io.github.jdubois.bootui.engine.codepaths;

/**
 * One request's call tree of application bean methods ({@code docs/PLAN-v2.md} §5.14, M5-4a), merged from the code-paths
 * fragments its threads flushed. Node 0 is the request; its children are the top-level methods of the request's own
 * threads, merged by method, and one {@link #ASYNC} node per child execution an executor ran for it, whose children are
 * that execution's methods. A node's {@linkplain #selfNanos self time} is its total minus its children's, never
 * negative; the request's own time is the union of its threads' fragments, and asynchronous children are shown apart,
 * never subtracted from it. At most {@value RequestTreeBuilder#MAX_NODES} nodes and {@value RequestTreeBuilder#MAX_DEPTH}
 * levels below the request: past the budget, a parent's further methods go to its one {@link #OTHER} node, and calls
 * that fit in neither stay in their parent's time. Immutable.
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
        long[] child) {

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
}
