package io.github.jdubois.bootui.agent.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code code-paths} sensor's bridge side (PLAN-v2 §5.14, M5-4a): per-thread fragments of a request's call tree of
 * application bean methods, built on the application thread from primitive arrays and handed to the engine as
 * {@code long[]} blobs.
 *
 * <p><b>Advice.</b> The agent inlines {@code int token = CodePaths.enter(id)} at the entry of every instrumented method
 * and {@code CodePaths.exit(token)} at its exit, on return and on a throwable, both suppressing their own exceptions. A
 * token is the thread's depth before the call plus one, tagged with the thread's frame epoch; 0 is the no-op sentinel,
 * which an inactive sensor, an excluded method ({@link #EXCLUDED}), or a suppressed exception yields, so enter and exit
 * always balance. An exit pops to its token's depth, which tolerates a missing exit, and ignores a token of an earlier
 * epoch, so a frame reset after an internal error never mismatches later exits.
 *
 * <p><b>Fragments.</b> A fragment starts at the outermost instrumented entry on a thread, or where an adapter calls
 * {@link #begin()} around a request's scope. It captures its owner once, through the claim's {@code capture} under the
 * re-entrancy guard: the request id and, for work handed to an executor, the execution id, both parsed to their 64 bits.
 * Without an owner, the thread only counts depth until the outermost call returns. With one, it borrows a tree from a
 * bounded pool (empty: the fragment is dropped, counted) and records one node per {@code (parent, method)} with its
 * calls, total time, children's time, and the request phase it entered in ({@link #phase}), up to
 * {@value #MAX_DEPTH} levels (deeper calls stay in the level-{@value #MAX_DEPTH} node's time) and {@value #MAX_NODES}
 * nodes, of which {@value #OTHER_RESERVE} are kept for one <b>Other</b> node per parent once the others are used; the
 * calls under an Other node stay in its time. The fragment is flushed when the depth returns to where it started, or at
 * {@link #end()}: encoded as one blob ({@link #HEADER} longs, then {@link #NODE} longs per node) into a lock-free queue
 * bounded by bytes (full: the blob is dropped, counted), which the claim's drainer takes with its token
 * ({@link #drain}). The tree goes back to the pool.
 *
 * <p><b>Safety.</b> JDK types only: the per-thread frame and the pooled trees are this package's classes holding
 * primitives and primitive arrays, never an application, framework, or engine object, so nothing here pins a class
 * loader. Every entry point catches everything; an internal error resets the thread's frame, and
 * {@value #MAX_ERRORS} of them switch the sensor off for the JVM's life ({@link #status}). A
 * {@link VirtualMachineError} caught there, as an application's runaway recursion overflowing its stack inside an
 * entry, resets the frame without counting toward that limit. The pool counts the trees of the current claim
 * generation only: a new generation counts the free ones, and a tree from an earlier one is dropped when it returns.
 *
 * <p><b>Debuggers.</b> The bridge is compiled without line numbers or local variable tables, so a debugger stepping
 * into an instrumented method steps over the inlined advice's calls into it rather than stopping inside BootUI.
 */
public final class CodePaths {

    public static final String SENSOR = "code-paths";

    /** The deepest level recorded in a fragment; deeper calls stay in the time of their level-32 ancestor. */
    public static final int MAX_DEPTH = 32;

    /** The most nodes in a fragment, Other nodes included. */
    public static final int MAX_NODES = 512;

    /** Nodes kept for Other nodes: regular nodes stop at {@code MAX_NODES - OTHER_RESERVE}. */
    public static final int OTHER_RESERVE = 32;

    /** The method id of an Other node. */
    public static final int OTHER = -1;

    /** Request phases a node entered in, as {@link #phase} receives them. */
    public static final int PHASE_UNKNOWN = 0;

    public static final int PHASE_FILTERS = 1;
    public static final int PHASE_HANDLER = 2;
    public static final int PHASE_RESPONSE = 3;

    /** Execution kinds, in the low bits of a blob's flags. */
    public static final int EXECUTION_NONE = 0;

    public static final int EXECUTION_ASYNC = 1;
    public static final int EXECUTION_TASK = 2;

    /** Flag: the fragment was opened by an adapter's {@link #begin()}. */
    public static final int FLAG_BEGUN = 16;

    /** Flag: calls were still open when the fragment was flushed, and were closed at the flush. */
    public static final int FLAG_CUT = 32;

    /** Blob layout: the format version, {@value #BLOB_VERSION}. */
    public static final int BLOB_VERSION = 1;

    public static final int H_VERSION = 0;
    public static final int H_GENERATION = 1;
    public static final int H_REQUEST = 2;
    public static final int H_EXECUTION = 3;
    public static final int H_FLAGS = 4;
    public static final int H_START_NANOS = 5;
    public static final int H_END_NANOS = 6;
    public static final int H_NODES = 7;
    public static final int H_DROPPED = 8;
    public static final int H_START_MILLIS = 9;

    /** Longs before the first node. */
    public static final int HEADER = 10;

    /** Node layout, relative to the node's first long: parent index (-1 for a top-level node). */
    public static final int N_PARENT = 0;

    public static final int N_METHOD = 1;
    public static final int N_PHASE = 2;
    public static final int N_CALLS = 3;
    public static final int N_TOTAL = 4;
    public static final int N_CHILD = 5;

    /** Longs per node. */
    public static final int NODE = 6;

    public static final int DEFAULT_POOL = 256;
    public static final int MAX_POOL = 4096;
    public static final long DEFAULT_QUEUE_BYTES = 4L << 20;
    public static final long MIN_QUEUE_BYTES = 64L << 10;
    public static final long MAX_QUEUE_BYTES = 256L << 20;

    /** Internal errors after which the sensor switches itself off for the JVM's life. */
    public static final int MAX_ERRORS = 100;

    /** Blobs handed over per drain at most, so one drain never runs unbounded. */
    static final int MAX_DRAIN = 4096;

    /** Bits of a token holding the depth plus one; the rest hold the frame's epoch. */
    static final int DEPTH_BITS = 12;

    static final int DEPTH_MASK = (1 << DEPTH_BITS) - 1;
    static final int EPOCH_MASK = (1 << (31 - DEPTH_BITS)) - 1;

    /** The deepest instrumented nesting a thread tracks at all; deeper calls get the no-op token. */
    static final int MAX_OPEN = DEPTH_MASK - 1;

    /** Per method id: set by the engine's adaptive exclusion through {@link #exclude}; read at every entry. */
    public static final byte[] EXCLUDED = new byte[CodeInventory.MAX_METHODS];

    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<Frame>();
    private static final ConcurrentLinkedQueue<Tree> FREE = new ConcurrentLinkedQueue<Tree>();
    private static final AtomicInteger CREATED = new AtomicInteger();
    private static final ConcurrentLinkedQueue<long[]> BLOBS = new ConcurrentLinkedQueue<long[]>();
    private static final AtomicLong QUEUED_BYTES = new AtomicLong();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static final AtomicBoolean DRAINING = new AtomicBoolean();
    private static final AtomicInteger EXCLUDED_COUNT = new AtomicInteger();
    private static final AtomicLong ERROR_COUNT = new AtomicLong();

    private static final LongAdder FRAGMENTS = new LongAdder();
    private static final LongAdder POOL_EMPTY = new LongAdder();
    private static final LongAdder QUEUE_FULL = new LongAdder();
    private static final LongAdder UNOWNED = new LongAdder();
    private static final LongAdder NODES = new LongAdder();
    private static final LongAdder DROPPED_CALLS = new LongAdder();
    private static final LongAdder CUT = new LongAdder();
    private static final LongAdder ABANDONED = new LongAdder();
    private static final LongAdder DRAINED = new LongAdder();
    private static final LongAdder STALE_DRAINS = new LongAdder();
    private static final LongAdder BUSY_DRAINS = new LongAdder();
    private static final LongAdder NEWER_STOPS = new LongAdder();
    private static final LongAdder SINK_ERRORS = new LongAdder();
    private static final LongAdder APPLICATION_ERRORS = new LongAdder();

    /** Whether an armed claim asks for the sensor and it is not switched off: read at every entry. */
    static volatile boolean active;

    /** The generation of the claim the sensor records for. */
    private static volatile long generation = -1L;

    private static volatile int poolMax = DEFAULT_POOL;
    private static volatile long queueMax = DEFAULT_QUEUE_BYTES;
    private static volatile boolean off;
    private static volatile String offReason;
    private static volatile long disabledGeneration = Long.MIN_VALUE;
    private static volatile String disabledReason;
    private static volatile Thread selfTestThread;

    /** Whether a claim ever asked for the sensor: until then its status is not reported. */
    static volatile boolean claimedOnce;

    private CodePaths() {}

    // ---- advice ----------------------------------------------------------------------------------------------------

    /**
     * Called at the entry of every instrumented method: its token for {@link #exit}, 0 when nothing is recorded for the
     * call (the sensor is inactive, the method is excluded, or the thread is nested too deep). Never throws.
     */
    public static int enter(int id) {
        try {
            if (!active && Thread.currentThread() != selfTestThread) {
                return 0;
            }
            if (EXCLUDED[id] != 0) {
                return 0;
            }
            Frame frame = FRAME.get();
            if (frame == null) {
                frame = new Frame();
                FRAME.set(frame);
            }
            int depth = frame.depth;
            if (depth >= MAX_OPEN) {
                return 0;
            }
            frame.depth = depth + 1;
            Tree tree = frame.tree;
            if (tree != null) {
                push(frame, tree, depth + 1 - frame.base, id);
            } else if (frame.untracked < 0) {
                start(frame, depth, id);
            }
            return ((frame.epoch & EPOCH_MASK) << DEPTH_BITS) | (depth + 1);
        } catch (Throwable ex) {
            failed(ex);
            return 0;
        }
    }

    /** Called at every exit of an instrumented method, normal or not, with the token its entry returned. Never throws. */
    public static void exit(int token) {
        if (token == 0) {
            return;
        }
        try {
            Frame frame = FRAME.get();
            if (frame == null) {
                return;
            }
            int target = (token & DEPTH_MASK) - 1;
            if ((token >>> DEPTH_BITS) != (frame.epoch & EPOCH_MASK) || target >= frame.depth) {
                // A token of an earlier epoch, or a call already popped by a later exit.
                return;
            }
            frame.depth = target;
            Tree tree = frame.tree;
            if (tree != null) {
                int level = Math.max(0, target - frame.base);
                closeTo(tree, level, System.nanoTime());
                if (target < frame.base || (target == frame.base && frame.begun != frame.base)) {
                    flush(frame);
                }
            }
            if (frame.untracked >= target) {
                frame.untracked = -1;
            }
            if (frame.begun > target) {
                frame.begun = -1;
                frame.nested = 0;
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    // ---- adapter boundaries ----------------------------------------------------------------------------------------

    /**
     * An adapter opened a request's scope on this thread: a fragment starts at the current depth, capturing the request
     * as its owner, and runs until {@link #end()}, so application filters outside the scope and the calls inside it are
     * apart. Does nothing while a fragment started by a call still open records on the thread. Inside a fragment an
     * earlier {@code begin()} opened, it is nested, as a Vert.x reroute inside the request's routing, and ignored with
     * its {@code end()}: while a call is open in that fragment, or when it captures the same owner. A fragment an
     * earlier {@code begin()} left open at this depth for another owner, its {@code end()} never called, is flushed
     * first, cut and counted. Never throws.
     */
    public static void begin() {
        try {
            if (!active) {
                return;
            }
            Frame frame = frame();
            long[] owner;
            Tree open = frame.tree;
            if (open != null) {
                if (frame.begun < 0 || frame.begun != frame.base) {
                    return;
                }
                if (frame.depth != frame.base) {
                    frame.nested++;
                    return;
                }
                owner = owner();
                if (owner != null && owner[0] == open.request && owner[1] == open.execution) {
                    frame.nested++;
                    return;
                }
                ABANDONED.increment();
                open.flags |= FLAG_CUT;
                closeTo(open, 0, System.nanoTime());
                flush(frame);
                frame.begun = -1;
                frame.nested = 0;
            } else {
                owner = owner();
            }
            if (owner == null) {
                return;
            }
            Tree tree = borrow();
            if (tree == null) {
                return;
            }
            tree.open(generation, owner[0], owner[1], (int) owner[2] | FLAG_BEGUN);
            frame.tree = tree;
            frame.base = frame.depth;
            frame.begun = frame.depth;
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /**
     * An adapter closed the request's scope on this thread: the fragment {@link #begin()} opened is flushed, closing any
     * call still open in it, and the thread's phase is forgotten; the end of a nested {@code begin()} does nothing. Runs
     * even after the claim was disarmed, so the tree returns to the pool. Never throws.
     */
    public static void end() {
        try {
            Frame frame = FRAME.get();
            if (frame == null) {
                return;
            }
            if (frame.nested > 0) {
                frame.nested--;
                return;
            }
            frame.phase = PHASE_UNKNOWN;
            Tree tree = frame.tree;
            if (tree != null && frame.begun >= 0 && frame.begun == frame.base) {
                if (tree.level > 0) {
                    tree.flags |= FLAG_CUT;
                    closeTo(tree, 0, System.nanoTime());
                }
                flush(frame);
                frame.begun = -1;
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /**
     * The request phase this thread is in, {@link #PHASE_FILTERS}, {@link #PHASE_HANDLER}, or {@link #PHASE_RESPONSE},
     * called where adapters mark it: nodes created from now on record it. Never throws.
     */
    public static void phase(int phase) {
        try {
            Frame frame = FRAME.get();
            if (frame == null) {
                if (!active) {
                    return;
                }
                frame = frame();
            }
            frame.phase = phase;
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /** The calling thread's open instrumented calls: the self-test's probe reads it. Never throws. */
    public static int depth() {
        Frame frame = FRAME.get();
        return frame == null ? 0 : frame.depth;
    }

    // ---- recording -------------------------------------------------------------------------------------------------

    private static Frame frame() {
        Frame frame = FRAME.get();
        if (frame == null) {
            frame = new Frame();
            FRAME.set(frame);
        }
        return frame;
    }

    /** The outermost call on the thread, entered at {@code depth}: captures its owner and starts recording, or not. */
    private static void start(Frame frame, int depth, int id) {
        // Until the owner is known, calls made by the capture itself only count depth.
        frame.untracked = depth;
        if (Thread.currentThread() == selfTestThread) {
            return;
        }
        long[] owner = owner();
        if (owner == null) {
            return;
        }
        Tree tree = borrow();
        if (tree == null) {
            return;
        }
        tree.open(generation, owner[0], owner[1], (int) owner[2]);
        frame.untracked = -1;
        frame.tree = tree;
        frame.base = depth;
        push(frame, tree, 1, id);
    }

    /**
     * The owner of the work on this thread: {@code {request, execution, executionKind}}, or {@code null} when the claim
     * is not armed for the sensor, the work is BootUI's own, a capture is already in progress, or nothing owns it.
     */
    private static long[] owner() {
        if (Reentrancy.bootUiWork() || !Reentrancy.enter()) {
            return null;
        }
        Object payload;
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != generation || !claim.hasSensor(SENSOR)) {
                return null;
            }
            Supplier<Object> capture = claim.capture.get();
            payload = capture == null ? null : capture.get();
        } finally {
            Reentrancy.exit();
        }
        if (!(payload instanceof Object[])) {
            UNOWNED.increment();
            return null;
        }
        Object[] values = (Object[]) payload;
        long request = values.length > 0 && values[0] instanceof String
                ? CodeInventory.parseRequestId((String) values[0])
                : 0L;
        long execution = 0L;
        long kind = EXECUTION_NONE;
        if (values.length > 1 && values[1] instanceof String) {
            String id = (String) values[1];
            if (id.startsWith("async-")) {
                execution = CodeInventory.parseRequestId(id.substring(6));
                kind = execution == 0L ? EXECUTION_NONE : EXECUTION_ASYNC;
            } else if (id.startsWith("task-")) {
                execution = CodeInventory.parseRequestId(id.substring(5));
                kind = execution == 0L ? EXECUTION_NONE : EXECUTION_TASK;
            }
        }
        if (request == 0L && execution == 0L) {
            UNOWNED.increment();
            return null;
        }
        return new long[] {request, execution, kind};
    }

    /** Opens a call at {@code level} (1 is the fragment's top) of {@code tree}. */
    private static void push(Frame frame, Tree tree, int level, int id) {
        tree.level = level;
        if (level > MAX_DEPTH || (tree.collapse != 0 && level > tree.collapse)) {
            if (level <= MAX_DEPTH) {
                tree.stackNode[level] = -1;
            }
            tree.dropped++;
            return;
        }
        int parent = level == 1 ? -1 : tree.stackNode[level - 1];
        int node = tree.node(parent, id, frame.phase);
        if (node < 0) {
            tree.stackNode[level] = -1;
            tree.collapse = level;
            tree.dropped++;
            return;
        }
        if (tree.method[node] == OTHER) {
            // Calls under an Other node stay in its time.
            tree.collapse = level;
        }
        tree.calls[node]++;
        tree.stackNode[level] = node;
        tree.stackStart[level] = System.nanoTime();
    }

    /** Closes the open calls above {@code level}, at {@code now}. */
    static void closeTo(Tree tree, int level, long now) {
        for (int l = Math.min(tree.level, MAX_DEPTH); l > level; l--) {
            int node = tree.stackNode[l];
            if (node >= 0) {
                long elapsed = now - tree.stackStart[l];
                tree.total[node] += elapsed;
                int parent = tree.parent[node];
                if (parent >= 0) {
                    tree.child[parent] += elapsed;
                }
                tree.stackNode[l] = -1;
            }
        }
        if (tree.level > level) {
            tree.level = level;
        }
        if (tree.collapse > level) {
            tree.collapse = 0;
        }
    }

    /**
     * Encodes the thread's fragment, queues it, and returns its tree to the pool; a fragment with no node and no dropped
     * call, as a request's scope in which no bean method ran, is not queued. A fragment {@link #begin()} did not open
     * also ends the phase the thread was marked in, so a worker thread's next work never inherits it.
     */
    private static void flush(Frame frame) {
        Tree tree = frame.tree;
        frame.tree = null;
        frame.base = -1;
        if (tree == null) {
            return;
        }
        if ((tree.flags & FLAG_BEGUN) == 0) {
            frame.phase = PHASE_UNKNOWN;
        }
        if (tree.count == 0 && tree.dropped == 0) {
            release(tree);
            return;
        }
        long[] blob = tree.encode(System.nanoTime());
        if ((tree.flags & FLAG_CUT) != 0) {
            CUT.increment();
        }
        NODES.add(tree.count);
        DROPPED_CALLS.add(tree.dropped);
        release(tree);
        offer(blob);
    }

    /**
     * Resets the thread's frame after a throwable inside an entry point. A {@link VirtualMachineError}, such as the
     * {@link StackOverflowError} of an application's runaway recursion or an {@link OutOfMemoryError}, is the
     * application's or the JVM's, not the sensor's: it resets the frame too, but never counts toward switching the
     * sensor off.
     */
    private static void failed(Throwable ex) {
        boolean application = ex instanceof VirtualMachineError;
        long errors = application ? 0L : ERROR_COUNT.incrementAndGet();
        try {
            if (application) {
                APPLICATION_ERRORS.increment();
            } else {
                AgentBridge.error(ex);
            }
            Frame frame = FRAME.get();
            if (frame != null) {
                if (frame.tree != null && frame.tree.generation == generation) {
                    // Possibly inconsistent: never back to the pool, and its slot is free again.
                    CREATED.decrementAndGet();
                }
                frame.tree = null;
                frame.base = -1;
                frame.begun = -1;
                frame.untracked = -1;
                frame.nested = 0;
                frame.depth = 0;
                frame.phase = PHASE_UNKNOWN;
                frame.epoch++;
            }
            if (!application && errors >= MAX_ERRORS && !off) {
                off = true;
                offReason = "switched off after " + MAX_ERRORS + " internal errors, the last: " + ex;
                active = false;
                AgentBridge.message("the code-paths sensor " + offReason);
            }
        } catch (Throwable ignored) {
            // Never throw from the error path.
        }
    }

    // ---- the pool --------------------------------------------------------------------------------------------------

    private static Tree borrow() {
        Tree tree = FREE.poll();
        if (tree != null) {
            return tree;
        }
        if (CREATED.incrementAndGet() <= poolMax) {
            return new Tree();
        }
        CREATED.decrementAndGet();
        POOL_EMPTY.increment();
        return null;
    }

    /**
     * Returns {@code tree} to the pool; a tree borrowed under an earlier claim generation is dropped instead, since a new
     * generation counted only the free trees as created.
     */
    private static void release(Tree tree) {
        boolean current = tree.generation == generation;
        tree.reset();
        if (!current) {
            return;
        }
        if (CREATED.get() > poolMax) {
            CREATED.decrementAndGet();
            return;
        }
        FREE.offer(tree);
    }

    // ---- the queue -------------------------------------------------------------------------------------------------

    /** Bytes a blob is counted for in the queue's bound. */
    static long bytes(long[] blob) {
        return 16L + 8L * blob.length;
    }

    private static void offer(long[] blob) {
        long size = bytes(blob);
        if (QUEUED_BYTES.addAndGet(size) > queueMax) {
            QUEUED_BYTES.addAndGet(-size);
            QUEUE_FULL.increment();
            return;
        }
        QUEUED.incrementAndGet();
        BLOBS.offer(blob);
        FRAGMENTS.increment();
    }

    /**
     * Hands the queued fragments, oldest first and at most {@value #MAX_DRAIN}, to {@code sink}, which owns each blob.
     * Only the current claim's token drains, one caller at a time; the drain stops at the first blob of a newer claim
     * generation, which belongs to that claim's drainer, while older blobs are handed over with their generation for the
     * sink to judge. Returns how many were drained.
     */
    public static int drain(long token, Consumer<long[]> sink) {
        try {
            if (sink == null) {
                return 0;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token) {
                STALE_DRAINS.increment();
                return 0;
            }
            if (!DRAINING.compareAndSet(false, true)) {
                BUSY_DRAINS.increment();
                return 0;
            }
            int drained = 0;
            try {
                while (drained < MAX_DRAIN) {
                    long[] blob = BLOBS.peek();
                    if (blob == null) {
                        break;
                    }
                    if (blob[H_GENERATION] > claim.generation) {
                        NEWER_STOPS.increment();
                        break;
                    }
                    BLOBS.poll();
                    QUEUED.decrementAndGet();
                    QUEUED_BYTES.addAndGet(-bytes(blob));
                    drained++;
                    DRAINED.increment();
                    try {
                        sink.accept(blob);
                    } catch (Throwable ex) {
                        SINK_ERRORS.increment();
                        AgentBridge.error(ex);
                    }
                }
            } finally {
                DRAINING.set(false);
            }
            return drained;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return 0;
        }
    }

    // ---- exclusion -------------------------------------------------------------------------------------------------

    /**
     * Excludes method {@code id} for the rest of the run: its entry yields the no-op token, so its time stays in its
     * caller. Only the current armed claim's token excludes. Returns whether the method is excluded now.
     */
    public static boolean exclude(long token, int id) {
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token || !claim.armed || id < 0 || id >= EXCLUDED.length) {
                return false;
            }
            if (EXCLUDED[id] == 0) {
                EXCLUDED[id] = 1;
                EXCLUDED_COUNT.incrementAndGet();
            }
            return true;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return false;
        }
    }

    /** The excluded method ids, ascending: a copy. */
    public static int[] excluded() {
        int count = Math.min(CodeInventory.methodCount(), EXCLUDED.length);
        int found = 0;
        int[] ids = new int[Math.max(0, EXCLUDED_COUNT.get())];
        for (int id = 0; id < count && found < ids.length; id++) {
            if (EXCLUDED[id] != 0) {
                ids[found++] = id;
            }
        }
        return found == ids.length ? ids : Arrays.copyOf(ids, found);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * A claim asking for the sensor was recorded: a new run, so no method is excluded any more, with the claim's pool
     * and queue bounds. Never throws.
     */
    static void claimed(Claim claim) {
        try {
            claimedOnce = true;
            if (claim.generation < generation) {
                return;
            }
            if (claim.generation != generation) {
                generation = claim.generation;
                // A tree a thread kept from an earlier generation, as one whose thread died inside a fragment, holds
                // no slot any more: only the free trees are counted, and an older tree is dropped when it comes back.
                CREATED.set(FREE.size());
            }
            poolMax = clamp(claim.codePathsPool, 1, MAX_POOL, DEFAULT_POOL);
            long requested = claim.codePathsQueueBytes;
            queueMax = requested <= 0
                    ? DEFAULT_QUEUE_BYTES
                    : Math.max(MIN_QUEUE_BYTES, Math.min(MAX_QUEUE_BYTES, requested));
            Arrays.fill(EXCLUDED, (byte) 0);
            EXCLUDED_COUNT.set(0);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Recomputes whether the sensor records, after any transition of the claim or the sensor. Never throws. */
    static void refresh() {
        try {
            Claim claim = AgentBridge.current();
            active = claim != null
                    && claim.armed
                    && claim.hasSensor(SENSOR)
                    && claim.generation == generation
                    && !off
                    && disabledGeneration != Long.MAX_VALUE
                    && disabledGeneration != claim.generation;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(max, value));
    }

    /** Loads and links what the advice and recording use, before the transformer instruments anything. */
    public static void warm() {
        try {
            Tree tree = new Tree();
            tree.open(-1L, 0L, 0L, 0);
            tree.node(-1, 0, 0);
            tree.encode(0L);
            tree.reset();
            bytes(new long[0]);
            depth();
            Reentrancy.guarded();
            status();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * Stops recording for {@code generation} whatever the advice still does, as when the self-test failed; with
     * {@code everyGeneration}, for good, as when the sensor also could not remove its transformer.
     */
    public static void disable(long disabled, boolean everyGeneration, String reason) {
        disabledGeneration = everyGeneration ? Long.MAX_VALUE : disabled;
        disabledReason = reason;
        refresh();
        AgentBridge.message("code-paths sensor disabled: " + reason);
    }

    /** Re-enables recording, after a later self-test passed. */
    public static void enable() {
        if (disabledGeneration != Long.MAX_VALUE) {
            disabledGeneration = Long.MIN_VALUE;
            disabledReason = null;
            refresh();
        }
    }

    /** Starts the self-test on the calling thread: its calls count depth, inactive or not, and record nothing. */
    public static void beginSelfTest() {
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test. */
    public static void endSelfTest() {
        selfTestThread = null;
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** Counters for the agent's status and the engine; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            map.put("generation", Long.valueOf(generation));
            map.put("active", Boolean.valueOf(active));
            map.put("fragmentsFlushed", Long.valueOf(FRAGMENTS.sum()));
            map.put("fragmentsDropped", Long.valueOf(POOL_EMPTY.sum()));
            map.put("queueDropped", Long.valueOf(QUEUE_FULL.sum()));
            map.put("unowned", Long.valueOf(UNOWNED.sum()));
            map.put("nodesRecorded", Long.valueOf(NODES.sum()));
            map.put("callsDropped", Long.valueOf(DROPPED_CALLS.sum()));
            map.put("cutFragments", Long.valueOf(CUT.sum()));
            map.put("abandonedFragments", Long.valueOf(ABANDONED.sum()));
            map.put("queued", Integer.valueOf(QUEUED.get()));
            map.put("queueBytes", Long.valueOf(QUEUED_BYTES.get()));
            map.put("queueMaxBytes", Long.valueOf(queueMax));
            map.put("poolMax", Integer.valueOf(poolMax));
            map.put("poolCreated", Integer.valueOf(CREATED.get()));
            map.put("poolFree", Integer.valueOf(FREE.size()));
            map.put("drained", Long.valueOf(DRAINED.sum()));
            map.put("staleDrains", Long.valueOf(STALE_DRAINS.sum()));
            map.put("busyDrains", Long.valueOf(BUSY_DRAINS.sum()));
            map.put("newerGenerationStops", Long.valueOf(NEWER_STOPS.sum()));
            map.put("sinkErrors", Long.valueOf(SINK_ERRORS.sum()));
            map.put("errors", Long.valueOf(ERROR_COUNT.get()));
            map.put("applicationErrors", Long.valueOf(APPLICATION_ERRORS.sum()));
            map.put("off", Boolean.valueOf(off));
            map.put("offReason", offReason);
            map.put("excludedMethods", Integer.valueOf(EXCLUDED_COUNT.get()));
            map.put("disabledReason", off ? offReason : disabledReason);
            Map<String, Object> recorded = new LinkedHashMap<String, Object>();
            // Fragments, not calls: the advice keeps no global counter.
            recorded.put("bean methods", Long.valueOf(FRAGMENTS.sum()));
            map.put("recorded", recorded);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** Tests only: forgets every fragment, tree, exclusion, and counter, and switches the sensor back on. */
    static void reset() {
        FRAME.remove();
        FREE.clear();
        CREATED.set(0);
        BLOBS.clear();
        QUEUED_BYTES.set(0);
        QUEUED.set(0);
        DRAINING.set(false);
        Arrays.fill(EXCLUDED, (byte) 0);
        EXCLUDED_COUNT.set(0);
        ERROR_COUNT.set(0);
        FRAGMENTS.reset();
        POOL_EMPTY.reset();
        QUEUE_FULL.reset();
        UNOWNED.reset();
        NODES.reset();
        DROPPED_CALLS.reset();
        CUT.reset();
        ABANDONED.reset();
        DRAINED.reset();
        STALE_DRAINS.reset();
        BUSY_DRAINS.reset();
        NEWER_STOPS.reset();
        SINK_ERRORS.reset();
        APPLICATION_ERRORS.reset();
        active = false;
        generation = -1L;
        poolMax = DEFAULT_POOL;
        queueMax = DEFAULT_QUEUE_BYTES;
        off = false;
        offReason = null;
        disabledGeneration = Long.MIN_VALUE;
        disabledReason = null;
        selfTestThread = null;
        claimedOnce = false;
    }

    /** Tests only: the calling thread's frame epoch. */
    static int epoch() {
        Frame frame = FRAME.get();
        return frame == null ? 0 : frame.epoch;
    }

    /** Tests only: whether the calling thread records a fragment now. */
    static boolean recordingFragment() {
        Frame frame = FRAME.get();
        return frame != null && frame.tree != null;
    }

    /** One thread's state: primitives and the tree it borrowed while a fragment records. */
    static final class Frame {

        /** Bumped when the frame is reset after an error: older tokens are ignored. */
        int epoch;

        /** Open instrumented calls with a non-zero token. */
        int depth;

        /** The depth the current fragment started at, or -1. */
        int base = -1;

        /** The depth of the outermost call recording nothing (no owner, no tree), or -1. */
        int untracked = -1;

        /** The depth {@link #begin()} opened the fragment at, or -1. */
        int begun = -1;

        /** The nested {@link #begin()} calls ignored inside the begun fragment, whose {@link #end()} is ignored too. */
        int nested;

        /** The request phase the thread is in, as the adapter marked it. */
        int phase;

        Tree tree;
    }

    /**
     * One fragment's tree: its header, its open calls by level, and its nodes, grown lazily from {@value #FIRST}
     * nodes to {@value #MAX_NODES}, with an open-addressing index of {@code (parent, method)}.
     */
    static final class Tree {

        static final int FIRST = 16;

        long generation;
        long request;
        long execution;
        int flags;
        long startNanos;
        long startMillis;
        int dropped;

        /** The level of the innermost open call, 0 when none. */
        int level;

        /** The level from which calls are collapsed into an ancestor, 0 when none. */
        int collapse;

        final int[] stackNode = new int[MAX_DEPTH + 1];
        final long[] stackStart = new long[MAX_DEPTH + 1];

        int count;
        int[] parent = new int[FIRST];
        int[] method = new int[FIRST];
        byte[] phase = new byte[FIRST];
        long[] calls = new long[FIRST];
        long[] total = new long[FIRST];
        long[] child = new long[FIRST];
        int[] index = new int[FIRST * 2];

        Tree() {
            Arrays.fill(stackNode, -1);
        }

        void open(long claimGeneration, long ownerRequest, long ownerExecution, int ownerFlags) {
            generation = claimGeneration;
            request = ownerRequest;
            execution = ownerExecution;
            flags = ownerFlags;
            startNanos = System.nanoTime();
            startMillis = System.currentTimeMillis();
        }

        /**
         * The node of {@code (parentNode, id)}: an existing one, a new one while regular nodes remain, else the
         * parent's Other node, created while the reserve lasts; -1 when none can be had.
         */
        int node(int parentNode, int id, int nodePhase) {
            int found = find(parentNode, id);
            if (found >= 0) {
                return found;
            }
            if (count < MAX_NODES - OTHER_RESERVE) {
                return add(parentNode, id, nodePhase);
            }
            int other = find(parentNode, OTHER);
            if (other >= 0) {
                return other;
            }
            return count < MAX_NODES ? add(parentNode, OTHER, nodePhase) : -1;
        }

        private static int key(int parentNode, int id) {
            return ((parentNode + 1) << 19) | (id + 1);
        }

        private static int slot(int key, int mask) {
            int hash = key * 0x9E3779B9;
            return (hash ^ (hash >>> 15)) & mask;
        }

        private int find(int parentNode, int id) {
            int key = key(parentNode, id);
            int mask = index.length - 1;
            for (int slot = slot(key, mask); ; slot = (slot + 1) & mask) {
                int entry = index[slot];
                if (entry == 0) {
                    return -1;
                }
                int node = entry - 1;
                if (parent[node] == parentNode && method[node] == id) {
                    return node;
                }
            }
        }

        private int add(int parentNode, int id, int nodePhase) {
            if (count == parent.length) {
                grow();
            }
            int node = count++;
            parent[node] = parentNode;
            method[node] = id;
            phase[node] = (byte) nodePhase;
            calls[node] = 0L;
            total[node] = 0L;
            child[node] = 0L;
            insert(node);
            return node;
        }

        private void insert(int node) {
            int mask = index.length - 1;
            int slot = slot(key(parent[node], method[node]), mask);
            while (index[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            index[slot] = node + 1;
        }

        private void grow() {
            int capacity = parent.length < 64 ? 64 : MAX_NODES;
            parent = Arrays.copyOf(parent, capacity);
            method = Arrays.copyOf(method, capacity);
            phase = Arrays.copyOf(phase, capacity);
            calls = Arrays.copyOf(calls, capacity);
            total = Arrays.copyOf(total, capacity);
            child = Arrays.copyOf(child, capacity);
            index = new int[capacity * 2];
            for (int node = 0; node < count; node++) {
                insert(node);
            }
        }

        long[] encode(long endNanos) {
            long[] blob = new long[HEADER + count * NODE];
            blob[H_VERSION] = BLOB_VERSION;
            blob[H_GENERATION] = generation;
            blob[H_REQUEST] = request;
            blob[H_EXECUTION] = execution;
            blob[H_FLAGS] = flags;
            blob[H_START_NANOS] = startNanos;
            blob[H_END_NANOS] = endNanos;
            blob[H_NODES] = count;
            blob[H_DROPPED] = dropped;
            blob[H_START_MILLIS] = startMillis;
            for (int node = 0; node < count; node++) {
                int base = HEADER + node * NODE;
                blob[base + N_PARENT] = parent[node];
                blob[base + N_METHOD] = method[node];
                blob[base + N_PHASE] = phase[node];
                blob[base + N_CALLS] = calls[node];
                blob[base + N_TOTAL] = total[node];
                blob[base + N_CHILD] = child[node];
            }
            return blob;
        }

        void reset() {
            Arrays.fill(index, 0);
            Arrays.fill(stackNode, -1);
            count = 0;
            level = 0;
            collapse = 0;
            dropped = 0;
            flags = 0;
            request = 0L;
            execution = 0L;
            generation = 0L;
        }
    }
}
