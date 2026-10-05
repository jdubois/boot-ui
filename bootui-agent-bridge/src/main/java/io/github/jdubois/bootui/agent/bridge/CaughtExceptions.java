package io.github.jdubois.bootui.agent.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The caught-exceptions sensor's bridge side (PLAN-v2 M5-6a): what application code does with the exceptions it
 * catches. The agent inserts, at the entry of each exception handler of an application class that names a type, a call
 * to {@link #caught} with the caught exception and the handler's site id, and appends to each method with such a
 * handler one catch-any handler calling {@link #leaving} before it rethrows, so an exception leaving the method by a
 * throw, its own or one a library helper made, is seen.
 *
 * <p><b>Sites.</b> A site is one handler, keyed by its class, method, descriptor, ordinal among the method's handlers,
 * and declared types, never by its line, so a DevTools restart, a live reload, or a retransformation of the same code
 * gets the same id; at most {@value #MAX_SITES} sites are kept for the JVM's life, since the ids are constants in the
 * transformed code, and a handler past the limit is not instrumented. The agent sets each site's flags once the whole
 * method was read: a handler added by another agent's inlined advice ({@link #FLAG_FOREIGN}, no line number at its
 * entry while the method has some) or one that is also a jump target ({@link #FLAG_SHARED}) is skipped at run time.
 *
 * <p><b>Records.</b> A caught exception publishes one {@code CAUGHT} record on the agent's ring: its owner (the request
 * and execution, from the thread's owner slot or a capture), its site, its class, its thread, and its identity
 * ({@code System.identityHashCode}), never its message, stack trace, or fields. Its identity, and those of up to
 * {@value #CHAIN} throwables of its cause and suppressed chain, are kept in a bounded table of pending identities: when
 * one of them later leaves an instrumented method by a throw, or is caught again by an instrumented handler, a
 * {@code THROWN} record names it, with the owner it was caught under, and the entry is freed. An entry is also freed
 * after {@value #PENDING_MILLIS} ms or at a newer claim generation; one evicted for room publishes an {@code EVICTED}
 * record, so the engine never treats its fate as known. On one thread, a site caught more than {@value #PER_SITE}
 * times for one owner publishes no more {@code CAUGHT} records for it: an {@code UNTRACKED} record counts them when the
 * thread's owner or its table entry changes, or every {@value #UNTRACKED_FLUSH} occurrences.
 *
 * <p>Nothing is recorded while no armed claim asks for the sensor, before its self-test passed, on BootUI's and the
 * agent's own threads, during BootUI's own work, a capture, or a transformation, or after {@value #MAX_ERRORS} internal
 * errors. The cause chain is walked under the bridge's re-entrancy guard, so a {@code getCause()} an application
 * overrides, catching an exception of its own, is never recorded inside it; no lock is held while application code
 * runs, and no monitor is taken at all: the table's entries are claimed by compare-and-set. JDK types only; every entry
 * point catches everything.
 */
public final class CaughtExceptions {

    /** The sensor's id, as {@code bootui.agent.sensors} names it. */
    public static final String SENSOR = "caught-exceptions";

    /** Record types. */
    public static final int TYPE_CAUGHT = 1;

    public static final int TYPE_THROWN = 2;
    public static final int TYPE_UNTRACKED = 3;
    public static final int TYPE_EVICTED = 4;

    /** How a pending identity was found again ({@code THROWN} records, payload long 3, bits 0–7). */
    public static final int THROWN_EXIT = 1;

    public static final int THROWN_CAUGHT_AGAIN = 2;

    /** Site flags. */
    public static final int FLAG_FOREIGN = 1;

    public static final int FLAG_SHARED = 2;

    /** The method has the appended exit handler, so its own throws are seen. */
    public static final int FLAG_EXIT_HANDLER = 4;

    /** In a constructor, whose throws are not seen. */
    public static final int FLAG_CONSTRUCTOR = 8;

    /** The flags set once the agent read the whole method. */
    public static final int FLAG_COMPLETE = 16;

    /** Class families ({@code CAUGHT} records, payload long 3, bits 0–1). */
    public static final int FAMILY_NONE = 0;

    public static final int FAMILY_SQL = 1;
    public static final int FAMILY_IO = 2;
    public static final int FAMILY_DATA_ACCESS = 3;

    /** The most sites kept for the JVM's life. */
    public static final int MAX_SITES = 16_384;

    /** Throwables of the cause and suppressed chain walked, the caught one included. */
    public static final int CHAIN = 8;

    /** Pending identities kept: {@value #STRIPES} stripes of {@value #STRIPE} entries. */
    static final int STRIPES = 64;

    static final int STRIPE = 64;

    public static final long PENDING_MILLIS = 60_000L;

    /** {@link #PENDING_MILLIS} on the monotonic clock pending entries are aged by. */
    static final long PENDING_NANOS = PENDING_MILLIS * 1_000_000L;

    /** How often the hooks free stale pending entries at most. */
    static final long SWEEP_MILLIS = 1_000L;

    static final long SWEEP_NANOS = SWEEP_MILLIS * 1_000_000L;

    /** Attempts to claim a pending entry another thread briefly owns. */
    static final int OWN_ATTEMPTS = 64;

    /** {@code CAUGHT} records per site and owner on one thread before they are only counted. */
    public static final int PER_SITE = 16;

    /** Counted occurrences after which an {@code UNTRACKED} record is published anyway. */
    public static final int UNTRACKED_FLUSH = 4_096;

    /** Sites a thread counts per owner. */
    static final int THREAD_SITES = 4;

    public static final int MAX_ERRORS = 100;

    /** Superclasses walked for the class family. */
    static final int FAMILY_DEPTH = 10;

    private static final String SQL_EXCEPTION = "java.sql.SQLException";
    private static final String IO_EXCEPTION = "java.io.IOException";
    private static final String DATA_ACCESS_EXCEPTION = "org.springframework.dao.DataAccessException";

    // ---- sites -----------------------------------------------------------------------------------------------------

    private static final ConcurrentHashMap<String, Integer> SITE_IDS = new ConcurrentHashMap<String, Integer>();
    private static final AtomicInteger NEXT_SITE = new AtomicInteger();
    private static final AtomicReferenceArray<String> SITE_KEYS = new AtomicReferenceArray<String>(MAX_SITES);
    private static final AtomicReferenceArray<String> SITE_TYPES = new AtomicReferenceArray<String>(MAX_SITES);
    private static final AtomicIntegerArray SITE_LINES = new AtomicIntegerArray(MAX_SITES);
    /** Read at every handler entry: written once per transformation of the site's method. */
    static final int[] SITE_FLAGS = new int[MAX_SITES];

    // ---- pending identities ----------------------------------------------------------------------------------------

    /** Each entry's state: {@link #FREE}, {@link #OWNED} by the thread writing or removing it, or {@link #LIVE}. */
    private static final AtomicIntegerArray P_STATE = new AtomicIntegerArray(STRIPES * STRIPE);

    private static final int FREE = 0;
    private static final int OWNED = 1;
    private static final int LIVE = 2;

    /** An entry's fields, written only by the thread that owns it, published by its state's volatile write. */
    private static final int[] P_IDENTITY = new int[STRIPES * STRIPE];

    private static final int[] P_CLASS = new int[STRIPES * STRIPE];
    private static final int[] P_SITE = new int[STRIPES * STRIPE];
    private static final long[] P_REQUEST = new long[STRIPES * STRIPE];
    private static final long[] P_EXECUTION = new long[STRIPES * STRIPE];
    private static final int[] P_KIND = new int[STRIPES * STRIPE];
    private static final long[] P_GENERATION = new long[STRIPES * STRIPE];
    /**
     * When the entry was made, from {@link System#nanoTime()}: a wall clock stepped back or forward neither keeps an
     * entry pending nor expires it early.
     */
    private static final long[] P_NANOS = new long[STRIPES * STRIPE];

    /** Entries in use: read first by {@link #leaving}, so a throw with nothing pending costs one volatile read. */
    private static final AtomicInteger PENDING = new AtomicInteger();

    private static final AtomicBoolean SWEEPING = new AtomicBoolean();
    /** When the last sweep ran, from {@link System#nanoTime()}; {@link Long#MIN_VALUE} before the first. */
    private static volatile long lastSweep = Long.MIN_VALUE;

    // ---- per thread ------------------------------------------------------------------------------------------------

    /**
     * A thread's counts: {@code [generation, request, execution, kind, threadKind, threadName, then per site: site,
     * published, untracked]}, a JDK type, so the thread-local pins no class loader.
     */
    private static final ThreadLocal<long[]> COUNTS = new ThreadLocal<long[]>();

    private static final int C_GENERATION = 0;
    private static final int C_REQUEST = 1;
    private static final int C_EXECUTION = 2;
    private static final int C_KIND = 3;
    private static final int C_THREAD_KIND = 4;
    private static final int C_THREAD_NAME = 5;
    private static final int C_SITES = 6;
    private static final int C_ENTRY = 3;

    /**
     * A thread's scratch, so a caught exception allocates nothing on its path: {@code [int[] chain identities,
     * Throwable[] walk, long[] owner]}. JDK types, so the thread-local pins no class loader; the walk's references to
     * the application's throwables are cleared once each walk ends, so it never retains one.
     */
    private static final ThreadLocal<Object[]> SCRATCH = new ThreadLocal<Object[]>();

    private static final int S_CHAIN = 0;
    private static final int S_WALK = 1;
    private static final int S_OWNER = 2;

    // ---- counters --------------------------------------------------------------------------------------------------

    private static final LongAdder CAUGHT = new LongAdder();
    private static final LongAdder PUBLISHED = new LongAdder();
    private static final LongAdder THROWN = new LongAdder();
    private static final LongAdder UNTRACKED = new LongAdder();
    private static final LongAdder EVICTED = new LongAdder();
    private static final LongAdder EXPIRED = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder UNOWNED = new LongAdder();
    private static final LongAdder LEAVING = new LongAdder();
    private static final LongAdder APPLICATION_ERRORS = new LongAdder();
    private static final LongAdder SITES_OVER_LIMIT = new LongAdder();
    /** Pending matches given up on because another thread held the entry: a rethrow possibly unrecorded. */
    private static final LongAdder MISSED = new LongAdder();

    private static final AtomicLong ERROR_COUNT = new AtomicLong();
    private static final AtomicLong SELF_TEST_CAUGHT = new AtomicLong();
    private static final AtomicLong SELF_TEST_THROWN = new AtomicLong();

    // ---- state -----------------------------------------------------------------------------------------------------

    /** Whether the sensor records now: read first by every entry point. */
    static volatile boolean active;

    private static volatile long generation = -1L;
    private static volatile boolean off;
    private static volatile String offReason;
    private static volatile long disabledGeneration = Long.MIN_VALUE;
    private static volatile String disabledReason;
    private static volatile Thread selfTestThread;
    private static volatile int selfTestIdentity;

    /** Whether a claim ever asked for the sensor: until then its status is not reported. */
    static volatile boolean claimedOnce;

    private CaughtExceptions() {}

    // ---- the advice's entry points ---------------------------------------------------------------------------------

    /**
     * An instrumented handler of site {@code site} caught {@code value}, typed {@code Object} so that verifying the
     * inserted call never needs the caught type's class hierarchy. Never throws, and never holds a lock while
     * application code runs.
     */
    public static void caught(Object value, int site) {
        try {
            Throwable thrown = value instanceof Throwable ? (Throwable) value : null;
            if (selfTestThread == Thread.currentThread()) {
                // The self-test's probe: counted, never recorded, whether a run records already or not.
                if (thrown != null) {
                    selfTestIdentity = System.identityHashCode(thrown);
                    SELF_TEST_CAUGHT.incrementAndGet();
                }
                return;
            }
            if (!active) {
                return;
            }
            if (thrown == null || site < 0 || site >= MAX_SITES) {
                return;
            }
            if ((SITE_FLAGS[site] & (FLAG_FOREIGN | FLAG_SHARED)) != 0) {
                SKIPPED.increment();
                return;
            }
            if (Reentrancy.sideEffectsSkipped()) {
                return;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != generation) {
                return;
            }
            CAUGHT.increment();
            long nanos = System.nanoTime();
            sweep(nanos);
            Object[] scratch = scratch();
            int[] chain = (int[]) scratch[S_CHAIN];
            long[] owner = (long[]) scratch[S_OWNER];
            // The chain, under the guard: an overridden getCause() catching its own exception records nothing. Only
            // the caught exception itself is needed unless something is pending, which a cause or a suppressed one
            // could be.
            int links;
            int family;
            if (!Reentrancy.enter()) {
                return;
            }
            try {
                links = PENDING.get() == 0 ? self(thrown, chain) : chain(thrown, chain, (Throwable[]) scratch[S_WALK]);
                family = family(thrown.getClass());
            } finally {
                Reentrancy.exit();
            }
            boolean owned = owner(claim, owner);
            if (!owned) {
                UNOWNED.increment();
            }
            // Found again: a pending identity in the chain was rethrown, wrapped or not, and caught here, under the
            // same request when both name one.
            found(chain, links, THROWN_CAUGHT_AGAIN, site, nanos, owned ? owner[0] : 0L);
            if (!owned) {
                return;
            }
            long[] counts = counts(claim.generation, owner);
            if (!count(counts, site)) {
                return;
            }
            long className = intern(thrown.getClass().getName());
            long threadName = counts[C_THREAD_NAME];
            int identity = chain[0];
            pend(identity, chain[1], site, owner, claim.generation, nanos);
            boolean published = AgentRing.publish(
                    AgentRing.SENSOR_CAUGHT_EXCEPTIONS,
                    TYPE_CAUGHT,
                    claim.generation,
                    System.currentTimeMillis(),
                    owner[0],
                    owner[1],
                    ((long) site << 32) | (identity & 0xFFFFFFFFL),
                    (className << 32)
                            | ((threadName & 0xFFFFL) << 16)
                            | ((owner[2] & 0xFL) << 8)
                            | ((counts[C_THREAD_KIND] & 0x3L) << 4)
                            | (family & 0x3L));
            if (published) {
                PUBLISHED.increment();
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /**
     * {@code thrown} leaves a method that has an instrumented handler, from its appended catch-any handler, which
     * rethrows it at once: the pending identities in its chain were rethrown. Never throws.
     */
    public static void leaving(Throwable thrown, int site) {
        try {
            if (selfTestThread == Thread.currentThread()) {
                if (thrown != null && System.identityHashCode(thrown) == selfTestIdentity) {
                    SELF_TEST_THROWN.incrementAndGet();
                }
                return;
            }
            if (!active || PENDING.get() == 0 || thrown == null || Reentrancy.sideEffectsSkipped()) {
                return;
            }
            long nanos = System.nanoTime();
            sweep(nanos);
            LEAVING.increment();
            Object[] scratch = scratch();
            int[] chain = (int[]) scratch[S_CHAIN];
            int links;
            if (!Reentrancy.enter()) {
                return;
            }
            try {
                links = chain(thrown, chain, (Throwable[]) scratch[S_WALK]);
            } finally {
                Reentrancy.exit();
            }
            found(chain, links, THROWN_EXIT, site, nanos, 0L);
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /** The calling thread's scratch, allocated on its first caught exception. */
    private static Object[] scratch() {
        Object[] scratch = SCRATCH.get();
        if (scratch == null) {
            scratch = new Object[] {new int[CHAIN * 2], new Throwable[CHAIN * 2], new long[3]};
            SCRATCH.set(scratch);
        }
        return scratch;
    }

    /** The identity of {@code thrown} and of its class, in {@code out}: one link. */
    static int self(Throwable thrown, int[] out) {
        out[0] = System.identityHashCode(thrown);
        out[1] = System.identityHashCode(thrown.getClass());
        return 1;
    }

    /** {@link #chain(Throwable, int[], Throwable[])} with a walk array of its own, for tests and warming. */
    static int chain(Throwable thrown, int[] out) {
        return chain(thrown, out, new Throwable[CHAIN * 2]);
    }

    /**
     * The identities of {@code thrown}, its causes, and their suppressed throwables, at most {@value #CHAIN}, each with
     * its class's identity, in {@code out}: returns how many. Cycles are cut by identity; a throwable whose
     * {@code getCause()} or {@code getSuppressed()} throws ends its branch. {@code walk} ({@code 2 * CHAIN} long) holds
     * the throwables seen, then those queued, and is cleared before returning. {@code getSuppressed()} allocates only
     * when a throwable has suppressed ones: the JDK answers a shared empty array otherwise.
     */
    static int chain(Throwable thrown, int[] out, Throwable[] walk) {
        int count = 0;
        int head = 0;
        int tail = 0;
        try {
            walk[CHAIN + tail++] = thrown;
            while (head < tail && count < CHAIN) {
                Throwable next = walk[CHAIN + head++];
                boolean known = false;
                for (int i = 0; i < count; i++) {
                    known |= walk[i] == next;
                }
                if (known) {
                    continue;
                }
                walk[count] = next;
                out[count * 2] = System.identityHashCode(next);
                out[count * 2 + 1] = System.identityHashCode(next.getClass());
                count++;
                try {
                    Throwable cause = next.getCause();
                    if (cause != null && cause != next && tail < CHAIN) {
                        walk[CHAIN + tail++] = cause;
                    }
                    Throwable[] suppressed = next.getSuppressed();
                    for (int i = 0; suppressed != null && i < suppressed.length && tail < CHAIN; i++) {
                        if (suppressed[i] != null) {
                            walk[CHAIN + tail++] = suppressed[i];
                        }
                    }
                } catch (Throwable ex) {
                    APPLICATION_ERRORS.increment();
                }
            }
            return count;
        } finally {
            for (int i = 0; i < walk.length; i++) {
                walk[i] = null;
            }
        }
    }

    /** {@code type}'s family: SQL, I/O, Spring data access, or none, by name, at most {@value #FAMILY_DEPTH} levels. */
    static int family(Class<?> type) {
        Class<?> current = type;
        for (int i = 0; current != null && i < FAMILY_DEPTH; i++) {
            String name = current.getName();
            if (SQL_EXCEPTION.equals(name)) {
                return FAMILY_SQL;
            }
            if (IO_EXCEPTION.equals(name)) {
                return FAMILY_IO;
            }
            if (DATA_ACCESS_EXCEPTION.equals(name)) {
                return FAMILY_DATA_ACCESS;
            }
            current = current.getSuperclass();
        }
        return FAMILY_NONE;
    }

    /**
     * The owner {@code {request, execution, executionKind}} into {@code out}: the thread's owner slot, else a capture
     * (which allocates the engine's snapshot, on the slow path only). Returns whether there is one.
     */
    private static boolean owner(Claim claim, long[] out) {
        CodePaths.Frame frame = CodePaths.FRAME.get();
        if (frame != null && frame.slotSource != null) {
            int index = frame.slots - 1;
            if (index >= 0
                    && index < SideEffects.SLOTS
                    && frame.slotGeneration[index] == claim.generation
                    && (frame.slotRequest[index] != 0L || frame.slotExecution[index] != 0L)) {
                out[0] = frame.slotRequest[index];
                out[1] = frame.slotExecution[index];
                out[2] = frame.slotKind[index];
                return true;
            }
        }
        // A request's, a request's task's, or an execution no request owns, as a scheduled run or a consumed message.
        return ownerOf(CodePaths.capture(claim), out);
    }

    /**
     * Reads a capture's request and execution into {@code out}, as {@code SideEffects.ownerOf} does, without its
     * holder: an execution id prefixed {@code async-} or {@code task-} is a request's, a bare one an execution no
     * request owns. Returns whether the capture named either.
     */
    static boolean ownerOf(Object payload, long[] out) {
        out[0] = 0L;
        out[1] = 0L;
        out[2] = CodePaths.EXECUTION_NONE;
        if (!(payload instanceof Object[])) {
            return false;
        }
        Object[] values = (Object[]) payload;
        if (values.length > 0 && values[0] instanceof String) {
            out[0] = CodeInventory.parseRequestId((String) values[0]);
        }
        if (values.length > 1 && values[1] instanceof String) {
            String id = (String) values[1];
            if (id.startsWith("async-")) {
                out[1] = CodeInventory.parseRequestId(id.substring(6));
                out[2] = CodePaths.EXECUTION_ASYNC;
            } else if (id.startsWith("task-")) {
                out[1] = CodeInventory.parseRequestId(id.substring(5));
                out[2] = CodePaths.EXECUTION_TASK;
            } else {
                out[1] = CodeInventory.parseRequestId(id);
                out[2] = SideEffects.EXECUTION_OWN;
            }
            if (out[1] == 0L) {
                out[2] = CodePaths.EXECUTION_NONE;
            }
        }
        return out[0] != 0L || out[1] != 0L;
    }

    /** The thread's counts, started over when its owner changed, the previous owner's untracked counts published. */
    private static long[] counts(long current, long[] owner) {
        long[] counts = COUNTS.get();
        if (counts == null) {
            counts = new long[C_SITES + THREAD_SITES * C_ENTRY];
            COUNTS.set(counts);
            counts[C_GENERATION] = -1L;
        }
        if (counts[C_GENERATION] != current
                || counts[C_REQUEST] != owner[0]
                || counts[C_EXECUTION] != owner[1]
                || counts[C_KIND] != owner[2]) {
            flushUntracked(counts);
            counts[C_GENERATION] = current;
            counts[C_REQUEST] = owner[0];
            counts[C_EXECUTION] = owner[1];
            counts[C_KIND] = owner[2];
            Thread thread = Thread.currentThread();
            counts[C_THREAD_KIND] = ThreadPropagation.isVirtual(thread) ? 2 : 1;
            counts[C_THREAD_NAME] = intern(thread.getName());
            for (int i = C_SITES; i < counts.length; i++) {
                counts[i] = 0L;
            }
        }
        return counts;
    }

    /**
     * Counts one occurrence of {@code site}: whether it is still published, under {@value #PER_SITE} for its owner on
     * this thread. A site the table has no room for takes the place of its first entry, whose count is published.
     */
    private static boolean count(long[] counts, int site) {
        int free = -1;
        for (int entry = C_SITES; entry < counts.length; entry += C_ENTRY) {
            if (counts[entry + 1] == 0L && counts[entry + 2] == 0L) {
                if (free < 0) {
                    free = entry;
                }
                continue;
            }
            if (counts[entry] == site) {
                if (counts[entry + 1] < PER_SITE) {
                    counts[entry + 1]++;
                    return true;
                }
                counts[entry + 2]++;
                UNTRACKED.increment();
                if (counts[entry + 2] >= UNTRACKED_FLUSH) {
                    publishUntracked(counts, entry);
                }
                return false;
            }
        }
        if (free < 0) {
            free = C_SITES;
            publishUntracked(counts, free);
        }
        counts[free] = site;
        counts[free + 1] = 1L;
        counts[free + 2] = 0L;
        return true;
    }

    private static void flushUntracked(long[] counts) {
        if (counts[C_GENERATION] < 0L) {
            return;
        }
        for (int entry = C_SITES; entry < counts.length; entry += C_ENTRY) {
            publishUntracked(counts, entry);
        }
    }

    /** Publishes the entry's untracked count, if any, under the thread's current owner, and zeroes it. */
    private static void publishUntracked(long[] counts, int entry) {
        long untracked = counts[entry + 2];
        if (untracked <= 0L) {
            return;
        }
        counts[entry + 2] = 0L;
        AgentRing.publish(
                AgentRing.SENSOR_CAUGHT_EXCEPTIONS,
                TYPE_UNTRACKED,
                counts[C_GENERATION],
                System.currentTimeMillis(),
                counts[C_REQUEST],
                counts[C_EXECUTION],
                (counts[entry] << 32) | (untracked & 0xFFFFFFFFL),
                ((counts[C_THREAD_NAME] & 0xFFFFL) << 16)
                        | ((counts[C_KIND] & 0xFL) << 8)
                        | ((counts[C_THREAD_KIND] & 0x3L) << 4));
    }

    /**
     * Publishes the counts the calling thread holds for its current owner, as when its work for that owner ends.
     * Never throws.
     */
    public static void flushThread() {
        try {
            long[] counts = COUNTS.get();
            if (counts != null) {
                flushUntracked(counts);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    // ---- the pending table -----------------------------------------------------------------------------------------

    /** Keeps the caught identity pending; an entry evicted for room, the stripe's oldest, publishes {@code EVICTED}. */
    private static void pend(int identity, int classIdentity, int site, long[] owner, long current, long nanos) {
        int base = ((identity & 0x7FFFFFFF) % STRIPES) * STRIPE;
        int slot = -1;
        boolean evicted = false;
        for (int i = base; i < base + STRIPE && slot < 0; i++) {
            if (P_STATE.get(i) == FREE && P_STATE.compareAndSet(i, FREE, OWNED)) {
                slot = i;
            }
        }
        if (slot < 0) {
            // Expired entries, or those of an older claim generation, are freed first: their fate stays unknown.
            for (int i = base; i < base + STRIPE && slot < 0; i++) {
                // Read before claiming: an entry that is still pending is never taken away from a concurrent match.
                if (P_STATE.get(i) == LIVE && stale(i, current, nanos) && P_STATE.compareAndSet(i, LIVE, OWNED)) {
                    if (stale(i, current, nanos)) {
                        EXPIRED.increment();
                        PENDING.decrementAndGet();
                        slot = i;
                    } else {
                        P_STATE.set(i, LIVE);
                    }
                }
            }
        }
        if (slot < 0) {
            int oldest = -1;
            long oldestAge = -1L;
            for (int i = base; i < base + STRIPE; i++) {
                long age = nanos - P_NANOS[i];
                if (P_STATE.get(i) == LIVE && age > oldestAge) {
                    oldest = i;
                    oldestAge = age;
                }
            }
            if (oldest < 0 || !P_STATE.compareAndSet(oldest, LIVE, OWNED)) {
                // Every entry changed hands meanwhile: this one is not kept, so its fate is unknown.
                EVICTED.increment();
                publishEvicted(owner[0], owner[1], (int) owner[2], site, identity, current);
                return;
            }
            slot = oldest;
            evicted = true;
            EVICTED.increment();
            publishEvicted(
                    P_REQUEST[slot],
                    P_EXECUTION[slot],
                    P_KIND[slot],
                    P_SITE[slot],
                    P_IDENTITY[slot],
                    P_GENERATION[slot]);
        }
        P_IDENTITY[slot] = identity;
        P_CLASS[slot] = classIdentity;
        P_SITE[slot] = site;
        P_REQUEST[slot] = owner[0];
        P_EXECUTION[slot] = owner[1];
        P_KIND[slot] = (int) owner[2];
        P_GENERATION[slot] = current;
        P_NANOS[slot] = nanos;
        if (!evicted) {
            PENDING.incrementAndGet();
        }
        P_STATE.set(slot, LIVE);
    }

    private static void publishEvicted(
            long request, long execution, int kind, int site, int identity, long caughtGeneration) {
        AgentRing.publish(
                AgentRing.SENSOR_CAUGHT_EXCEPTIONS,
                TYPE_EVICTED,
                caughtGeneration,
                System.currentTimeMillis(),
                request,
                execution,
                ((long) site << 32) | (identity & 0xFFFFFFFFL),
                (kind & 0xFL) << 8);
    }

    /**
     * Frees the pending entries whose identity and class identity are in {@code chain}, publishing a {@code THROWN}
     * record for each, with the owner it was caught under. With {@code request} non-zero, an entry another request
     * caught is left alone: an identity hash two requests' exceptions share is never taken for a rethrow across them.
     */
    private static void found(int[] chain, int links, int how, int site, long nanos, long request) {
        if (PENDING.get() == 0) {
            return;
        }
        long current = generation;
        for (int link = 0; link < links; link++) {
            int identity = chain[link * 2];
            int classIdentity = chain[link * 2 + 1];
            int base = ((identity & 0x7FFFFFFF) % STRIPES) * STRIPE;
            for (int i = base; i < base + STRIPE; i++) {
                if (!candidate(i, identity, classIdentity) || otherRequest(i, request)) {
                    continue;
                }
                if (!own(i)) {
                    // Held by another thread past every retry: this throw may go unrecorded, so it is counted.
                    if (candidate(i, identity, classIdentity)) {
                        MISSED.increment();
                    }
                    continue;
                }
                if (P_IDENTITY[i] != identity || P_CLASS[i] != classIdentity || otherRequest(i, request)) {
                    // Another entry took the place meanwhile.
                    P_STATE.set(i, LIVE);
                    continue;
                }
                long caughtRequest = P_REQUEST[i];
                long execution = P_EXECUTION[i];
                int kind = P_KIND[i];
                int caughtSite = P_SITE[i];
                long caughtGeneration = P_GENERATION[i];
                boolean expired = stale(i, current, nanos);
                PENDING.decrementAndGet();
                P_STATE.set(i, FREE);
                if (expired) {
                    // Of an older run, or pending past its time: freed, never reported as its owner's rethrow.
                    EXPIRED.increment();
                    continue;
                }
                THROWN.increment();
                AgentRing.publish(
                        AgentRing.SENSOR_CAUGHT_EXCEPTIONS,
                        TYPE_THROWN,
                        caughtGeneration,
                        System.currentTimeMillis(),
                        caughtRequest,
                        execution,
                        ((long) caughtSite << 32) | (identity & 0xFFFFFFFFL),
                        ((long) site << 32) | ((kind & 0xFL) << 8) | (how & 0xFFL));
                break;
            }
        }
    }

    /** Whether entry {@code i} was caught under another request than {@code request}, when both name one. */
    private static boolean otherRequest(int i, long request) {
        return request != 0L && P_REQUEST[i] != 0L && P_REQUEST[i] != request;
    }

    /** Whether entry {@code i} looks like a live entry of this identity: read without claiming it. */
    private static boolean candidate(int i, int identity, int classIdentity) {
        int state = P_STATE.get(i);
        return state != FREE && P_IDENTITY[i] == identity && P_CLASS[i] == classIdentity;
    }

    /** Claims a live entry, retrying while another thread briefly owns it. */
    private static boolean own(int i) {
        for (int attempt = 0; attempt < OWN_ATTEMPTS; attempt++) {
            int state = P_STATE.get(i);
            if (state == FREE) {
                return false;
            }
            if (state == LIVE && P_STATE.compareAndSet(i, LIVE, OWNED)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /** Whether entry {@code i} is of another claim generation than {@code current}, or pending past its time. */
    private static boolean stale(int i, long current, long nanos) {
        return P_GENERATION[i] != current || nanos - P_NANOS[i] > PENDING_NANOS;
    }

    /**
     * At most once a second, from the hooks: frees every entry of another generation or pending past its time, so a
     * handled exception does not stay pending, keeping {@link #leaving} on its one-read fast path once nothing is.
     */
    private static void sweep(long nanos) {
        long last = lastSweep;
        if ((last != Long.MIN_VALUE && nanos - last < SWEEP_NANOS) || !SWEEPING.compareAndSet(false, true)) {
            return;
        }
        try {
            lastSweep = nanos;
            freeStale(generation, nanos);
        } finally {
            SWEEPING.set(false);
        }
    }

    /** Frees the entries of another generation than {@code current} or pending past their time. */
    private static void freeStale(long current, long nanos) {
        for (int i = 0; i < STRIPES * STRIPE; i++) {
            if (P_STATE.get(i) == LIVE && stale(i, current, nanos) && P_STATE.compareAndSet(i, LIVE, OWNED)) {
                if (stale(i, current, nanos)) {
                    EXPIRED.increment();
                    PENDING.decrementAndGet();
                    P_STATE.set(i, FREE);
                } else {
                    P_STATE.set(i, LIVE);
                }
            }
        }
    }

    /** Pending entries, for tests and the status. */
    static int pending() {
        return PENDING.get();
    }

    /** Tests only: the entries in the {@code LIVE} state, which once quiescent must equal {@link #pending()}. */
    static int liveEntries() {
        int live = 0;
        for (int i = 0; i < STRIPES * STRIPE; i++) {
            live += P_STATE.get(i) == LIVE ? 1 : 0;
        }
        return live;
    }

    /** Tests only: whether any entry is left {@code OWNED}, which once quiescent none may be. */
    static boolean anyOwned() {
        for (int i = 0; i < STRIPES * STRIPE; i++) {
            if (P_STATE.get(i) == OWNED) {
                return true;
            }
        }
        return false;
    }

    // ---- sites -----------------------------------------------------------------------------------------------------

    /**
     * The id of the handler {@code key} ({@code class#method+descriptor#ordinal#types}), registered now if new, with its
     * declared types ({@code |}-separated internal names): {@code -1} past {@value #MAX_SITES} sites, when the handler
     * is left uninstrumented. Called by the agent while it transforms a class. Never throws.
     */
    public static int site(String key, String types) {
        try {
            Integer known = SITE_IDS.get(key);
            if (known != null) {
                return known.intValue();
            }
            int id = NEXT_SITE.getAndIncrement();
            if (id >= MAX_SITES) {
                NEXT_SITE.set(MAX_SITES);
                SITES_OVER_LIMIT.increment();
                return -1;
            }
            SITE_KEYS.set(id, key);
            SITE_TYPES.set(id, types);
            Integer raced = SITE_IDS.putIfAbsent(key, Integer.valueOf(id));
            // Another transformation of the same method registered it first: this id stays unused.
            return raced == null ? id : raced.intValue();
        } catch (Throwable ex) {
            failed(ex);
            return -1;
        }
    }

    /** Sets the site's flags and line ({@code 0} when unknown), once the agent read its whole method. Never throws. */
    public static void siteRead(int site, int flags, int line) {
        try {
            if (site >= 0 && site < MAX_SITES) {
                SITE_FLAGS[site] = flags | FLAG_COMPLETE;
                // A volatile write after the flags: the engine reading the line sees them.
                SITE_LINES.set(site, line);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /**
     * The sites from id {@code from}, each {@code key, types, line, flags} joined by tabs, index 0 being id
     * {@code from}: a copy, for the engine to name what records refer to. Never throws.
     */
    public static String[] sites(int from) {
        try {
            int count = siteCount();
            int start = Math.max(0, from);
            if (start >= count) {
                return new String[0];
            }
            String[] copy = new String[count - start];
            for (int i = start; i < count; i++) {
                int line = SITE_LINES.get(i);
                String key = SITE_KEYS.get(i);
                copy[i - start] =
                        key == null ? null : key + '\t' + SITE_TYPES.get(i) + '\t' + line + '\t' + SITE_FLAGS[i];
            }
            return copy;
        } catch (Throwable ex) {
            failed(ex);
            return new String[0];
        }
    }

    /** Sites registered. */
    public static int siteCount() {
        return Math.min(NEXT_SITE.get(), MAX_SITES);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * A claim asking for the sensor was recorded: the ring and its intern table for the generation, and a fresh pending
     * table, since a newer generation's run is another application run. Never throws.
     */
    static void claimed(Claim claim) {
        try {
            claimedOnce = true;
            if (claim.generation < generation) {
                return;
            }
            AgentRing.newGeneration(claim.generation, claim.ringCapacity);
            generation = claim.generation;
            // A newer run: the previous run's pending identities are never its rethrows.
            freeStale(claim.generation, System.nanoTime());
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
                    && disabledGeneration != claim.generation
                    && enabledOnce;
            // The owner slots adapters and handoffs push are read by this sensor too (PLAN-v2 M5-6a).
            SideEffects.slotReaders(active, generation);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Whether a self-test ever passed: until then the hooks record nothing. */
    private static volatile boolean enabledOnce;

    /**
     * Stops recording until the next self-test passes, as when the agent removes or reinstalls the sensor's visit, so
     * a later claim never records before its own self-test.
     */
    public static void suspend() {
        enabledOnce = false;
        refresh();
    }

    /** Starts recording, once the self-test passed. */
    public static void enable() {
        enabledOnce = true;
        if (disabledGeneration != Long.MAX_VALUE) {
            disabledGeneration = Long.MIN_VALUE;
            disabledReason = null;
        }
        refresh();
    }

    /**
     * Stops recording for {@code disabled} whatever the hooks still do, as when the self-test failed; with
     * {@code everyGeneration}, for good, as when the agent also could not remove its visit.
     */
    public static void disable(long disabled, boolean everyGeneration, String reason) {
        disabledGeneration = everyGeneration ? Long.MAX_VALUE : disabled;
        disabledReason = reason;
        refresh();
        AgentBridge.message("caught-exceptions sensor disabled: " + reason);
    }

    /** Starts the self-test on the calling thread: its hooks count and record nothing while the sensor is inactive. */
    public static void beginSelfTest() {
        SELF_TEST_CAUGHT.set(0L);
        SELF_TEST_THROWN.set(0L);
        selfTestIdentity = 0;
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test: {@code {caught, thrown}} hits seen on its thread. */
    public static long[] endSelfTest() {
        selfTestThread = null;
        return new long[] {SELF_TEST_CAUGHT.get(), SELF_TEST_THROWN.get()};
    }

    /** Loads and links what the hooks and the agent's registration use, before any class is transformed. */
    public static void warm() {
        try {
            int[] chain = new int[CHAIN * 2];
            chain(new IllegalStateException("warm", new RuntimeException()), chain);
            self(new IllegalStateException("warm"), chain);
            ownerOf(new Object[] {"0000000000000001", "async-0000000000000002"}, new long[3]);
            family(IllegalStateException.class);
            SITE_IDS.get("warm");
            SITE_LINES.get(0);
            P_STATE.get(0);
            sites(Integer.MAX_VALUE);
            intern(null);
            status();
            Reentrancy.guarded();
            Reentrancy.sideEffectsSkipped();
            ThreadPropagation.isVirtual(Thread.currentThread());
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static long intern(String text) {
        return AgentRing.intern(text) & 0xFFFFFFFFL;
    }

    private static void failed(Throwable ex) {
        AgentBridge.error(ex);
        if (ERROR_COUNT.incrementAndGet() >= MAX_ERRORS && !off) {
            off = true;
            offReason = "switched off after " + MAX_ERRORS + " internal errors, the last " + ex;
            active = false;
            AgentBridge.message("caught-exceptions sensor " + offReason);
        }
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** Counters for the agent's status and the engine; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            map.put("generation", Long.valueOf(generation));
            map.put("active", Boolean.valueOf(active));
            map.put("caught", Long.valueOf(CAUGHT.sum()));
            map.put("published", Long.valueOf(PUBLISHED.sum()));
            map.put("thrown", Long.valueOf(THROWN.sum()));
            map.put("untracked", Long.valueOf(UNTRACKED.sum()));
            map.put("evicted", Long.valueOf(EVICTED.sum()));
            map.put("expired", Long.valueOf(EXPIRED.sum()));
            map.put("pending", Integer.valueOf(PENDING.get()));
            map.put("skipped", Long.valueOf(SKIPPED.sum()));
            map.put("unowned", Long.valueOf(UNOWNED.sum()));
            map.put("exits", Long.valueOf(LEAVING.sum()));
            map.put("sites", Integer.valueOf(siteCount()));
            map.put("sitesOverLimit", Long.valueOf(SITES_OVER_LIMIT.sum()));
            map.put("missed", Long.valueOf(MISSED.sum()));
            map.put("dropped", Long.valueOf(AgentRing.dropped(AgentRing.SENSOR_CAUGHT_EXCEPTIONS)));
            map.put("errors", Long.valueOf(ERROR_COUNT.get()));
            map.put("applicationErrors", Long.valueOf(APPLICATION_ERRORS.sum()));
            map.put("off", Boolean.valueOf(off));
            map.put("disabledReason", off ? offReason : disabledReason);
            Map<String, Object> recorded = new LinkedHashMap<String, Object>();
            recorded.put("handler entry", Long.valueOf(CAUGHT.sum()));
            recorded.put("exceptional exit", Long.valueOf(THROWN.sum()));
            map.put("recorded", recorded);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** Tests only: forgets the state, the sites, and the counters. */
    static void reset() {
        active = false;
        generation = -1L;
        off = false;
        offReason = null;
        disabledGeneration = Long.MIN_VALUE;
        disabledReason = null;
        enabledOnce = false;
        selfTestThread = null;
        claimedOnce = false;
        SITE_IDS.clear();
        for (int i = 0; i < MAX_SITES; i++) {
            SITE_KEYS.set(i, null);
            SITE_TYPES.set(i, null);
            SITE_LINES.set(i, 0);
            SITE_FLAGS[i] = 0;
        }
        NEXT_SITE.set(0);
        lastSweep = Long.MIN_VALUE;
        SCRATCH.remove();
        for (int i = 0; i < P_NANOS.length; i++) {
            P_STATE.set(i, FREE);
        }
        PENDING.set(0);
        COUNTS.remove();
        for (LongAdder adder : new LongAdder[] {
            CAUGHT,
            PUBLISHED,
            THROWN,
            UNTRACKED,
            EVICTED,
            EXPIRED,
            SKIPPED,
            UNOWNED,
            LEAVING,
            APPLICATION_ERRORS,
            SITES_OVER_LIMIT,
            MISSED
        }) {
            adder.reset();
        }
        ERROR_COUNT.set(0L);
    }
}
