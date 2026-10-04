package io.github.jdubois.bootui.engine.codepaths;

/**
 * One fragment of a request's call tree as the BootUI agent's {@code code-paths} sensor flushed it from one thread
 * ({@code docs/PLAN-v2.md} §5.14, M5-4a), decoded from its {@code long[]} blob (the bridge's {@code CodePaths} layout).
 * Nodes are in creation order, so a node's parent always comes before it; a top-level node's parent is -1, and an
 * {@link #OTHER} node holds the calls of its parent past the fragment's node budget. Arrays are owned by the fragment
 * and never changed once decoded.
 *
 * @param generation the claim generation the fragment was recorded under
 * @param request the owning request id's 64 bits, 0 when only an execution owns the work
 * @param execution the owning child execution id's 64 bits, 0 for the request's own threads
 * @param executionKind {@link #EXECUTION_NONE}, {@link #EXECUTION_ASYNC} ({@code async-}), or {@link #EXECUTION_TASK}
 *     ({@code task-})
 * @param flags {@link #FLAG_BEGUN} and {@link #FLAG_CUT}
 * @param startNanos when the fragment started, on the JVM's {@code System.nanoTime()} clock
 * @param endNanos when it was flushed
 * @param startMillis when it started, in epoch milliseconds
 * @param dropped calls recorded in no node: past the depth cap, under an Other node, or past the node budget
 * @param parent each node's parent index, -1 for a top-level node
 * @param method each node's method id, or {@link #OTHER}
 * @param phase each node's request phase: 0 unknown, 1 filters, 2 handler, 3 response
 * @param calls each node's calls
 * @param total each node's total time in nanoseconds, its children's included
 * @param child each node's children's total time in nanoseconds
 */
public record CodePathFragment(
        long generation,
        long request,
        long execution,
        int executionKind,
        int flags,
        long startNanos,
        long endNanos,
        long startMillis,
        long dropped,
        int[] parent,
        int[] method,
        byte[] phase,
        long[] calls,
        long[] total,
        long[] child) {

    /** The method id of an Other node ({@code CodePaths.OTHER}). */
    public static final int OTHER = -1;

    public static final int EXECUTION_NONE = 0;
    public static final int EXECUTION_ASYNC = 1;
    public static final int EXECUTION_TASK = 2;

    /** Opened by an adapter around the request's scope. */
    public static final int FLAG_BEGUN = 16;

    /** Calls were still open when it was flushed. */
    public static final int FLAG_CUT = 32;

    /** The blob format version this engine reads. */
    static final int VERSION = 1;

    static final int H_VERSION = 0;
    static final int H_GENERATION = 1;
    static final int H_REQUEST = 2;
    static final int H_EXECUTION = 3;
    static final int H_FLAGS = 4;
    static final int H_START_NANOS = 5;
    static final int H_END_NANOS = 6;
    static final int H_NODES = 7;
    static final int H_DROPPED = 8;
    static final int H_START_MILLIS = 9;
    static final int HEADER = 10;
    static final int NODE = 6;

    /** The most nodes a fragment may carry ({@code CodePaths.MAX_NODES}). */
    static final int MAX_NODES = 512;

    /** The fragment's node count. */
    public int nodeCount() {
        return parent.length;
    }

    /** Whether a child execution, an executor handoff, owns it. */
    public boolean handoff() {
        return executionKind != EXECUTION_NONE && execution != 0L;
    }

    /** Its duration in nanoseconds, never negative. */
    public long durationNanos() {
        return Math.max(0L, endNanos - startNanos);
    }

    /**
     * Decodes a blob, or returns {@code null} for one of another version or malformed: a wrong length, a node whose
     * parent does not precede it, or negative counts. Never throws.
     */
    public static CodePathFragment decode(long[] blob) {
        try {
            if (blob == null || blob.length < HEADER || blob[H_VERSION] != VERSION) {
                return null;
            }
            long count = blob[H_NODES];
            if (count < 0 || count > MAX_NODES || blob.length != HEADER + count * NODE) {
                return null;
            }
            int nodes = (int) count;
            int[] parent = new int[nodes];
            int[] method = new int[nodes];
            byte[] phase = new byte[nodes];
            long[] calls = new long[nodes];
            long[] total = new long[nodes];
            long[] child = new long[nodes];
            for (int node = 0; node < nodes; node++) {
                int base = HEADER + node * NODE;
                long parentIndex = blob[base];
                long methodId = blob[base + 1];
                long nodePhase = blob[base + 2];
                if (parentIndex < -1
                        || parentIndex >= node
                        || methodId < OTHER
                        || methodId > Integer.MAX_VALUE
                        || nodePhase < 0
                        || nodePhase > 3
                        || blob[base + 3] < 0
                        || blob[base + 4] < 0
                        || blob[base + 5] < 0) {
                    return null;
                }
                parent[node] = (int) parentIndex;
                method[node] = (int) methodId;
                phase[node] = (byte) nodePhase;
                calls[node] = blob[base + 3];
                total[node] = blob[base + 4];
                child[node] = Math.min(blob[base + 5], blob[base + 4]);
            }
            long flags = blob[H_FLAGS];
            return new CodePathFragment(
                    blob[H_GENERATION],
                    blob[H_REQUEST],
                    blob[H_EXECUTION],
                    (int) (flags & 15),
                    (int) (flags & ~15L),
                    blob[H_START_NANOS],
                    Math.max(blob[H_START_NANOS], blob[H_END_NANOS]),
                    blob[H_START_MILLIS],
                    Math.max(0L, blob[H_DROPPED]),
                    parent,
                    method,
                    phase,
                    calls,
                    total,
                    child);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The 16-hexadecimal-digit form of an id's 64 bits, as BootUI writes request and execution ids. */
    public static String hex(long id) {
        String digits = Long.toHexString(id);
        return "0".repeat(16 - digits.length()) + digits;
    }
}
