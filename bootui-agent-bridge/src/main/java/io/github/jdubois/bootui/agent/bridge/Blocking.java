package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@value SideEffects#BLOCKING} sensor's bridge side (PLAN-v2 §5.16, M5-5c): {@code Thread.sleep},
 * {@code Object.wait}, and {@code LockSupport.park} <b>started</b> on an event loop, reported with their frames, never
 * thrown.
 *
 * <p><b>Event loops.</b> The bridge never decides what an event loop is: an adapter registers the thread it classifies
 * as one ({@link #registerEventLoop()}), as Reactor Netty's loops on Spring WebFlux and Vert.x's on Quarkus, when it
 * first sees it run a request or a client response. Loops live in a table of at most {@value #SLOTS} slots keyed by
 * thread id, which the JVM never reuses, each entry stamped with the claim generation that registered it and holding its
 * thread weakly, so a dead loop's slot is reused and a loop of an earlier run, as after a Quarkus live reload, matches
 * only once registered again. Lookups and inserts probe at most {@value #PROBES} slots.
 *
 * <p><b>Fast path.</b> Every hook first reads {@link SideEffects#mask} once: until a loop is registered for the
 * generation, nothing else happens, so a stack without event loops pays one volatile read per hook. Then the thread's
 * id is looked up, usually an empty slot. Only a hook started on a registered loop takes the slow path: the duration,
 * the owner, the code-paths stamp, and the frame summary, aggregated in the thread's table as every side-effect record.
 * A park shorter than {@value #MIN_PARK_NANOS} ns, such as a library handing a lock over, is only counted; sleeps and
 * waits are always recorded, a zero or negative one never.
 *
 * <p><b>Hooks.</b> {@code LockSupport.park}, {@code parkNanos}, and {@code parkUntil} are advised in the JDK
 * ({@link #parking()}, {@link #parked}). {@code Thread.sleep} and {@code Object.wait} are {@code native} on JDK 17, and
 * on later JDKs end in native methods on platform threads, so the agent rewrites their call sites in the application's
 * classes to the substitutes here ({@link #sleep(long)}, {@link #waitOn(Object)}), which call the original and record
 * around it; a library's own sleep is not seen. A blocking network operation the {@code network} sensor records is
 * reported from its hook ({@link #networkOnLoop}); file operations will report through {@link #starting} and {@link
 * #done} from their own hooks.
 *
 * <p>The static initializer creates only JDK objects and never blocks. JDK types only; every entry point catches
 * everything but what the original call throws.
 */
public final class Blocking {

    /** Record kinds of the blocking sensor. */
    public static final int KIND_SLEEP = 1;

    public static final int KIND_WAIT = 2;
    public static final int KIND_PARK = 3;

    /** Reserved for the network and files sensors' operations started on an event loop. */
    public static final int KIND_NETWORK = 4;

    public static final int KIND_FILE = 5;

    /** Outcomes. */
    public static final int OUTCOME_RETURNED = 1;

    public static final int OUTCOME_INTERRUPTED = 2;
    public static final int OUTCOME_ERROR = 3;

    /** The shortest park recorded: shorter ones on an event loop are only counted. */
    public static final long MIN_PARK_NANOS = 1_000_000L;

    /** The event-loop table's slots, a power of two. */
    static final int SLOTS = 1 << 10;

    /** The slots one lookup or insert probes at most. */
    static final int PROBES = 8;

    private static final AtomicReferenceArray<Loop> TABLE = new AtomicReferenceArray<Loop>(SLOTS);
    private static final LongAdder REGISTRATIONS = new LongAdder();
    /** Registrations refused because the loop's probed slots were all taken: counted per attempt. */
    private static final LongAdder FULL = new LongAdder();

    private static final LongAdder SHORT_PARKS = new LongAdder();
    private static final LongAdder STARTED = new LongAdder();

    /** The generation an adapter last registered a loop for: {@link SideEffects#refresh()} reads it. */
    static volatile long loopsGeneration = -1L;

    /** The agent thread self-testing the call-site hooks, while it does. */
    static volatile Thread callSiteTestThread;

    private Blocking() {}

    /** A registered event loop. */
    static final class Loop {
        final long id;
        final WeakReference<Thread> thread;
        final long generation;
        final int name;

        Loop(long id, Thread thread, long generation, int name) {
            this.id = id;
            this.thread = new WeakReference<Thread>(thread);
            this.generation = generation;
            this.name = name;
        }
    }

    // ---- event loops ----------------------------------------------------------------------------------------------

    /**
     * Registers the calling thread, which the adapter classifies as an event loop, for the current claim generation:
     * a no-op unless the armed claim asks for the blocking sensor, and when the thread is already registered for it.
     * Never throws.
     */
    public static void registerEventLoop() {
        try {
            if ((SideEffects.claimedBits & SideEffects.MASK_BLOCKING) == 0) {
                return;
            }
            Thread thread = Thread.currentThread();
            long current = SideEffects.generation;
            long id = thread.getId();
            for (int attempt = 0; attempt < PROBES; attempt++) {
                int start = index(id);
                int free = -1;
                Loop freeLoop = null;
                Loop own = null;
                int ownSlot = -1;
                for (int i = 0; i < PROBES; i++) {
                    int slot = (start + i) & (SLOTS - 1);
                    Loop loop = TABLE.get(slot);
                    if (loop == null) {
                        if (free < 0) {
                            free = slot;
                        }
                        break;
                    }
                    if (loop.id == id) {
                        own = loop;
                        ownSlot = slot;
                        break;
                    }
                    if (free < 0 && loop.thread.get() == null) {
                        free = slot;
                        freeLoop = loop;
                    }
                }
                if (own != null) {
                    if (own.generation == current) {
                        activated(current);
                        return;
                    }
                    // Registered by an earlier run: stamped again, with its name in this run's table.
                    if (TABLE.compareAndSet(
                            ownSlot, own, new Loop(id, thread, current, SideEffects.intern(thread.getName())))) {
                        REGISTRATIONS.increment();
                        activated(current);
                        return;
                    }
                    continue;
                }
                if (free < 0) {
                    FULL.increment();
                    return;
                }
                if (TABLE.compareAndSet(
                        free, freeLoop, new Loop(id, thread, current, SideEffects.intern(thread.getName())))) {
                    REGISTRATIONS.increment();
                    activated(current);
                    return;
                }
                // Another loop took the slot first: probe again.
            }
            FULL.increment();
        } catch (Throwable ex) {
            SideEffects.failed(ex);
        }
    }

    private static void activated(long registered) {
        if (loopsGeneration != registered) {
            loopsGeneration = registered;
            SideEffects.refresh();
        }
    }

    /** The registered loop of thread id {@code id}, of any generation, or {@code null}. */
    static Loop find(long id) {
        int start = index(id);
        for (int i = 0; i < PROBES; i++) {
            Loop loop = TABLE.get((start + i) & (SLOTS - 1));
            if (loop == null) {
                return null;
            }
            if (loop.id == id) {
                return loop;
            }
        }
        return null;
    }

    /** The first slot of thread id {@code id}: the top bits of a Fibonacci hash, as ids are sequential. */
    static int index(long id) {
        return (int) ((id * 0x9E3779B97F4A7C15L) >>> (64 - Integer.numberOfTrailingZeros(SLOTS)));
    }

    // ---- the shared entry and exit ------------------------------------------------------------------------------

    /**
     * A blocking operation of hook {@code hook} starts on this thread: a token for {@link #done}, 0 when nothing is
     * recorded (not on a registered event loop of this run, the sensor is off, the thread's work is skipped, or another
     * side-effect hook is open on the thread). The network and files sensors call it from their own hooks before they
     * open theirs. Never throws.
     */
    static long starting(int hook) {
        int bits = SideEffects.mask;
        if ((bits & (SideEffects.MASK_LOOPS | SideEffects.MASK_SELF_TEST)) == 0) {
            return 0L;
        }
        return slowStart(bits, hook);
    }

    private static long slowStart(int bits, int hook) {
        try {
            Thread thread = Thread.currentThread();
            if ((bits & SideEffects.MASK_SELF_TEST) != 0
                    && (thread == SideEffects.selfTestThread || thread == callSiteTestThread)) {
                SideEffects.SELF_TEST_HITS[hook].increment();
                return 0L;
            }
            if ((bits & SideEffects.MASK_LOOPS) == 0) {
                return 0L;
            }
            Loop loop = find(thread.getId());
            if (loop == null || loop.generation != SideEffects.generation) {
                return 0L;
            }
            if (Reentrancy.sideEffectsSkipped()) {
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            long now = System.nanoTime();
            if (frame.sideEffectDepth != 0 && now - frame.sideEffectSince < SideEffects.STALE_DEPTH_NANOS) {
                return 0L;
            }
            frame.sideEffectDepth = 1;
            frame.sideEffectSince = now;
            STARTED.increment();
            return now == 0L ? 1L : now;
        } catch (Throwable ex) {
            SideEffects.failed(ex);
            return 0L;
        }
    }

    /**
     * The operation {@link #starting} returned {@code token} for ended, normally or with {@code thrown}: records it as
     * {@code kind}, unless it is a park shorter than {@value #MIN_PARK_NANOS} ns that returned normally. Never throws.
     */
    static void done(long token, int kind, int hook, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            if (kind == KIND_PARK && nanos < MIN_PARK_NANOS && thrown == null) {
                // A lock handed over at once; a park that threw, as BlockHound's refusal, is always recorded.
                SHORT_PARKS.increment();
                return;
            }
            Claim claim = AgentBridge.current();
            if (claim == null
                    || !claim.armed
                    || claim.generation != SideEffects.generation
                    || (SideEffects.mask & SideEffects.MASK_BLOCKING) == 0) {
                return;
            }
            Thread thread = Thread.currentThread();
            Loop loop = find(thread.getId());
            int target = loop != null && loop.generation == claim.generation ? loop.name : 0;
            SideEffects.RECORDED[hook].increment();
            int outcome = thrown == null
                    ? OUTCOME_RETURNED
                    : thrown instanceof InterruptedException ? OUTCOME_INTERRUPTED : OUTCOME_ERROR;
            long stamp = CodePaths.stamp();
            long frames = SideEffects.frames(claim);
            SideEffects.Owner owner = SideEffects.owner(frame, claim);
            SideEffects.record(
                    frame, owner, SideEffects.SENSOR_BLOCKING, kind, target, outcome, 0, stamp, frames, nanos);
        } catch (Throwable ex) {
            SideEffects.failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    // ---- network operations on an event loop ------------------------------------------------------------------

    /**
     * A blocking network operation the {@code network} sensor recorded ended, its hook still open on this thread: when
     * it started on a registered event loop and the blocking sensor records, it is recorded too, as {@link
     * #KIND_NETWORK} with the network record's frames, so a blocking connect, a name lookup, or a blocking datagram send
     * on an event loop is reported. The network sensor decides which of its operations block: a socket's connect, a
     * channel's in blocking mode, a lookup the JVM's name service answered, a {@code DatagramSocket} send; never
     * Netty's non-blocking connect or its finish. Called inside the network hook, so the thread's hook stays the
     * network sensor's. Never throws.
     */
    static void networkOnLoop(long token, long frames, Throwable thrown) {
        try {
            int bits = SideEffects.mask;
            if ((bits & SideEffects.MASK_LOOPS) == 0 || (bits & SideEffects.MASK_BLOCKING) == 0 || token == 0L) {
                return;
            }
            Loop loop = find(Thread.currentThread().getId());
            Claim claim = AgentBridge.current();
            if (loop == null || claim == null || !claim.armed || loop.generation != claim.generation) {
                return;
            }
            long nanos = System.nanoTime() - token;
            STARTED.increment();
            SideEffects.RECORDED[SideEffects.HOOK_NETWORK_ON_LOOP].increment();
            int outcome = thrown == null
                    ? OUTCOME_RETURNED
                    : thrown instanceof java.io.InterruptedIOException ? OUTCOME_INTERRUPTED : OUTCOME_ERROR;
            CodePaths.Frame frame = CodePaths.FRAME.get();
            SideEffects.record(
                    frame,
                    SideEffects.owner(frame, claim),
                    SideEffects.SENSOR_BLOCKING,
                    KIND_NETWORK,
                    loop.name,
                    outcome,
                    0,
                    CodePaths.stamp(),
                    frames,
                    nanos);
        } catch (Throwable ex) {
            SideEffects.failed(ex);
        }
    }

    // ---- LockSupport.park advice -------------------------------------------------------------------------------

    /** {@code LockSupport.park*} entry: a token for {@link #parked}, 0 off a registered event loop. Never throws. */
    public static long parking() {
        return starting(SideEffects.HOOK_PARK);
    }

    /** {@code LockSupport.park*} exit, normal or not, with the token its entry returned. Never throws. */
    public static void parked(long token, Throwable thrown) {
        if (token != 0L) {
            done(token, KIND_PARK, SideEffects.HOOK_PARK, thrown);
        }
    }

    // ---- call-site substitutes ---------------------------------------------------------------------------------

    /** Replaces {@code Thread.sleep(long)} in application classes: calls it, recorded on an event loop. */
    public static void sleep(long millis) throws InterruptedException {
        long token = millis > 0L ? starting(SideEffects.HOOK_SLEEP) : 0L;
        if (token == 0L) {
            Thread.sleep(millis);
            return;
        }
        Throwable thrown = null;
        try {
            Thread.sleep(millis);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_SLEEP, SideEffects.HOOK_SLEEP, thrown);
        }
    }

    /** Replaces {@code Thread.sleep(long, int)} in application classes. */
    public static void sleep(long millis, int nanos) throws InterruptedException {
        long token = millis > 0L || (millis == 0L && nanos > 0) ? starting(SideEffects.HOOK_SLEEP) : 0L;
        if (token == 0L) {
            Thread.sleep(millis, nanos);
            return;
        }
        Throwable thrown = null;
        try {
            Thread.sleep(millis, nanos);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_SLEEP, SideEffects.HOOK_SLEEP, thrown);
        }
    }

    /** Replaces {@code Thread.sleep(Duration)} (JDK 19 and later) in application classes. */
    public static void sleep(Duration duration) throws InterruptedException {
        long token = duration != null && !duration.isNegative() && !duration.isZero()
                ? starting(SideEffects.HOOK_SLEEP)
                : 0L;
        if (token == 0L) {
            DurationSleep.sleep(duration);
            return;
        }
        Throwable thrown = null;
        try {
            DurationSleep.sleep(duration);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_SLEEP, SideEffects.HOOK_SLEEP, thrown);
        }
    }

    /** Replaces {@code TimeUnit.sleep(long)} in application classes, the unit as the receiver. */
    public static void sleep(TimeUnit unit, long timeout) throws InterruptedException {
        long token = unit != null && timeout > 0L ? starting(SideEffects.HOOK_SLEEP) : 0L;
        if (token == 0L) {
            unit.sleep(timeout);
            return;
        }
        Throwable thrown = null;
        try {
            unit.sleep(timeout);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_SLEEP, SideEffects.HOOK_SLEEP, thrown);
        }
    }

    /** Replaces {@code Object.wait()} in application classes, the receiver first: the monitor is the thread's. */
    public static void waitOn(Object monitor) throws InterruptedException {
        long token = monitor != null ? starting(SideEffects.HOOK_WAIT) : 0L;
        if (token == 0L) {
            monitor.wait();
            return;
        }
        Throwable thrown = null;
        try {
            monitor.wait();
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_WAIT, SideEffects.HOOK_WAIT, thrown);
        }
    }

    /** Replaces {@code Object.wait(long)} in application classes. */
    public static void waitOn(Object monitor, long millis) throws InterruptedException {
        long token = monitor != null && millis >= 0L ? starting(SideEffects.HOOK_WAIT) : 0L;
        if (token == 0L) {
            monitor.wait(millis);
            return;
        }
        Throwable thrown = null;
        try {
            monitor.wait(millis);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_WAIT, SideEffects.HOOK_WAIT, thrown);
        }
    }

    /** Replaces {@code Object.wait(long, int)} in application classes. */
    public static void waitOn(Object monitor, long millis, int nanos) throws InterruptedException {
        long token = monitor != null && millis >= 0L ? starting(SideEffects.HOOK_WAIT) : 0L;
        if (token == 0L) {
            monitor.wait(millis, nanos);
            return;
        }
        Throwable thrown = null;
        try {
            monitor.wait(millis, nanos);
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            done(token, KIND_WAIT, SideEffects.HOOK_WAIT, thrown);
        }
    }

    /**
     * {@code Thread.sleep(Duration)}, which this Java 17 bridge calls reflectively, found on first use: the bridge never
     * touches {@code java.lang.invoke}, which may still be bootstrapping inside an advised JDK method.
     */
    static final class DurationSleep {

        private static final java.lang.reflect.Method SLEEP = find();

        private static java.lang.reflect.Method find() {
            try {
                return Thread.class.getMethod("sleep", Duration.class);
            } catch (Throwable ex) {
                return null;
            }
        }

        static void sleep(Duration duration) throws InterruptedException {
            if (SLEEP == null) {
                // Only an application compiled for a later JDK calls it: the original call would fail the same way.
                throw new NoSuchMethodError("java.lang.Thread.sleep(java.time.Duration)");
            }
            try {
                SLEEP.invoke(null, duration);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof InterruptedException) {
                    throw (InterruptedException) cause;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    // ---- self-test, status, lifecycle ------------------------------------------------------------------------

    /** The agent starts self-testing the call-site hooks on the calling thread: their hits are counted, not recorded. */
    public static void beginCallSiteSelfTest() {
        SideEffects.SELF_TEST_HITS[SideEffects.HOOK_SLEEP].reset();
        SideEffects.SELF_TEST_HITS[SideEffects.HOOK_WAIT].reset();
        callSiteTestThread = Thread.currentThread();
        SideEffects.refresh();
    }

    /** Ends the call-site self-test: the sleep hook's hits (0) and the wait hook's (1). */
    public static long[] endCallSiteSelfTest() {
        callSiteTestThread = null;
        SideEffects.refresh();
        return new long[] {
            SideEffects.SELF_TEST_HITS[SideEffects.HOOK_SLEEP].sum(),
            SideEffects.SELF_TEST_HITS[SideEffects.HOOK_WAIT].sum()
        };
    }

    /** The loops registered for {@code generation} and still alive. */
    static int loops(long generation) {
        int count = 0;
        for (int i = 0; i < SLOTS; i++) {
            Loop loop = TABLE.get(i);
            if (loop != null && loop.generation == generation && loop.thread.get() != null) {
                count++;
            }
        }
        return count;
    }

    static void putStatus(Map<String, Object> map, long generation) {
        map.put("eventLoops", Integer.valueOf(loops(generation)));
        map.put("eventLoopRegistrations", Long.valueOf(REGISTRATIONS.sum()));
        map.put("eventLoopRegistrationsRefused", Long.valueOf(FULL.sum()));
        map.put("startedOnEventLoops", Long.valueOf(STARTED.sum()));
        map.put("shortParks", Long.valueOf(SHORT_PARKS.sum()));
        map.put("minParkNanos", Long.valueOf(MIN_PARK_NANOS));
    }

    /** Loads and links what the hooks and the substitutes use, before the transformers install. */
    static void warm() {
        find(Thread.currentThread().getId());
        index(1L);
        new Loop(-1L, Thread.currentThread(), -2L, 0).thread.get();
        long token = starting(SideEffects.HOOK_PARK);
        done(token, KIND_PARK, SideEffects.HOOK_PARK, null);
        parked(parking(), null);
        DurationSleep.class.getName();
        putStatus(new java.util.LinkedHashMap<String, Object>(), -1L);
    }

    /** Tests only: forgets every registered loop and counter. */
    static void reset() {
        for (int i = 0; i < SLOTS; i++) {
            TABLE.set(i, null);
        }
        REGISTRATIONS.reset();
        FULL.reset();
        SHORT_PARKS.reset();
        STARTED.reset();
        loopsGeneration = -1L;
        callSiteTestThread = null;
    }
}
