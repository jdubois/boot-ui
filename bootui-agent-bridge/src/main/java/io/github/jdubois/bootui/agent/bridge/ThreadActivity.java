package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The {@value SideEffects#THREAD_ACTIVITY} sensor's bridge side (PLAN-v2 §5.16, M5-5e): the threads the application
 * starts and the executors it creates, per route or thread family and call site, the threads and executors a request's
 * application code left running when the request ended, and the executors shut down or reclaimed by the collector
 * without a shutdown. Never a thread-local, a task, or anything a thread or executor holds.
 *
 * <p><b>Hooks.</b> {@code Thread.start} and {@code VirtualThread.start(ThreadContainer)} at entry and exit; the
 * canonical constructors of {@code ThreadPoolExecutor}, {@code ForkJoinPool}, and {@code ThreadPerTaskExecutor} at exit;
 * their {@code shutdown}, {@code shutdownNow}, and {@code close} at entry; and, as pool marks, {@code
 * ThreadPoolExecutor.addWorker} and {@code ThreadPerTaskExecutor.start(Thread)}: a thread a pool starts is the pool's,
 * never recorded on its own ({@link CodePaths.Frame#poolStarts}). A {@code ForkJoinWorkerThread}, the JDK's {@code
 * DelayScheduler}, and a thread whose first starting frame is in {@code java.util.concurrent} are pool workers too.
 *
 * <p><b>Origin.</b> One bounded walk ({@link CreatorWalk}) skips the threading API's own frames and names the immediate
 * creator: in the JDK, or below a static initializer, the thread or executor is the JDK's or a static singleton's,
 * recorded but never tracked; else the first frame outside the JDK decides, as M5-2's threads sensor keys a thread: in
 * the claimed packages, the application's, otherwise a library's (a framework's pool, a client, an {@code @Async}
 * executor). Only the application's threads and executors, started or created by work a request or an execution owns,
 * are tracked ({@link ThreadTracker}), weakly, so a request's end can say what it left running.
 *
 * <p><b>Hot paths.</b> A request-owned platform thread start or any executor creation is rare and walks every time. A
 * virtual thread start, or a start no request owns (a server starting a virtual thread per request), reuses the walk of
 * its first sighting, keyed by the starting thread's family, the target, the thread's kind, and the code-paths call
 * site (at most {@value #MAX_SIGHTINGS} per claim generation); an owned one aggregates in the thread's table, an unowned
 * one in its sighting, which the drain thread publishes ({@link #sweep}).
 *
 * <p>JDK types only; every entry point catches everything.
 */
public final class ThreadActivity {

    /** The detail of a thread-activity record ({@link SideEffects#R_FLAGS} bits 32–39): its origin, bits 0–1. */
    public static final int ORIGIN_APPLICATION = 1;

    public static final int ORIGIN_LIBRARY = 2;
    public static final int ORIGIN_JDK = 3;

    /** The started thread is virtual. */
    public static final int DETAIL_VIRTUAL = 1 << 2;

    /**
     * Created once for the application's life: in a static initializer, or while a container creates a singleton bean
     * ({@link #singletonCreation}), never tracked.
     */
    public static final int DETAIL_STATIC = 1 << 3;

    /** An executor's kind, bits 4–7. */
    public static final int EXECUTOR_THREAD_POOL = 1;

    public static final int EXECUTOR_SCHEDULED = 2;
    public static final int EXECUTOR_FORK_JOIN = 3;
    public static final int EXECUTOR_PER_TASK = 4;

    /** A walk's verdict that a thread start is a pool's worker: not recorded. */
    static final int WORKER = 4;

    /** Sightings remembered per claim generation, and distinct targets interned. */
    static final int MAX_SIGHTINGS = 1_024;

    static final int MAX_TARGETS = 256;

    static final String OTHER_THREADS = "(other threads)";

    static final String UNNAMED = "(unnamed)";

    /** How long after its end a request's threads are checked: one still unwinding as the response completes is not. */
    static final long GRACE_NANOS = 250_000_000L;

    /** Frames walked at most. */
    static final int MAX_FRAMES = 64;

    /** JDK singletons started lazily, never tracked. */
    private static final String[] JDK_THREADS = {
        "process reaper", "Keep-Alive-Timer", "CompletableFutureDelayScheduler", "Common-Cleaner", "Attach Listener"
    };

    static final ThreadTracker TRACKER = new ThreadTracker();

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final AtomicReference<State> STATE = new AtomicReference<State>();

    private static final LongAdder WORKERS = new LongAdder();
    private static final LongAdder JDK_SINGLETONS = new LongAdder();
    private static final LongAdder WALKS = new LongAdder();
    private static final LongAdder SIGHTINGS_FULL = new LongAdder();
    private static final LongAdder TARGETS_OVERFLOW = new LongAdder();
    private static final LongAdder TRACKED = new LongAdder();
    private static final LongAdder LEFT_RUNNING = new LongAdder();
    private static final LongAdder RECLAIMED = new LongAdder();
    private static final LongAdder SHUT_DOWN = new LongAdder();

    private ThreadActivity() {}

    /** One claim generation's sightings and targets. */
    static final class State {
        final long generation;
        final ConcurrentHashMap<Key, Sighting> sightings = new ConcurrentHashMap<Key, Sighting>();
        final ConcurrentHashMap<String, Integer> targets = new ConcurrentHashMap<String, Integer>();
        /** Started threads' names that are their own family to its target, so a name seen again builds no string. */
        final ConcurrentHashMap<String, Integer> names = new ConcurrentHashMap<String, Integer>();

        State(long generation) {
            this.generation = generation;
        }
    }

    /**
     * An unowned start's sighting key: the starting thread's family, the target, the detail, the call site, and the
     * started thread's class, so a pool's worker and another thread of the same name never share a verdict.
     */
    static final class Key {
        int family;
        int target;
        int detail;
        long site;
        int type;

        Key(int family, int target, int detail, long site, int type) {
            set(family, target, detail, site, type);
        }

        /** Reused as a thread's lookup probe ({@code CodePaths.Frame#threadProbe}), copied before it is kept. */
        Key set(int family, int target, int detail, long site, int type) {
            this.family = family;
            this.target = target;
            this.detail = detail;
            this.site = site;
            this.type = type;
            return this;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return family == key.family
                    && target == key.target
                    && detail == key.detail
                    && site == key.site
                    && type == key.type;
        }

        @Override
        public int hashCode() {
            int hash = family * 31 + target;
            hash = hash * 31 + detail;
            hash = hash * 31 + (int) (site ^ (site >>> 32));
            return hash * 31 + type;
        }
    }

    /** What a sighting's first walk found, and the unowned starts counted since the drain thread last published. */
    static final class Sighting {
        final long frames;
        final int origin;
        final boolean isStatic;
        final LongAdder pending = new LongAdder();
        volatile long firstMillis;
        volatile long lastMillis;

        Sighting(long frames, int origin, boolean isStatic) {
            this.frames = frames;
            this.origin = origin;
            this.isStatic = isStatic;
        }
    }

    // ---- hooks -------------------------------------------------------------------------------------------------

    /**
     * {@code Thread.start} or {@code VirtualThread.start(ThreadContainer)} entry, {@code hook} saying which: a token for
     * {@link #threadStarted}, 0 when nothing is recorded (the sensor is off, a pool starts its worker, the thread is a
     * pool's, BootUI's, or the agent's, the starting thread's work is skipped, or a side-effect hook is open on it). The
     * self-test's thread is counted per hook and records nothing. Never throws.
     */
    public static long threadStarting(Object thread, int hook) {
        if ((SideEffects.gate & SideEffects.MASK_THREADS) == 0) {
            return 0L;
        }
        try {
            Thread current = Thread.currentThread();
            if (current == SideEffects.selfTestThread) {
                SideEffects.SELF_TEST_HITS[hook].increment();
                return 0L;
            }
            if ((SideEffects.mask & SideEffects.MASK_THREADS) == 0 || !(thread instanceof Thread)) {
                return 0L;
            }
            if (worker((Thread) thread)) {
                WORKERS.increment();
                return 0L;
            }
            if (Reentrancy.sideEffectsSkipped() || current.getName().startsWith("bootui-")) {
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            if (frame.poolStarts > 0) {
                WORKERS.increment();
                return 0L;
            }
            return open(frame);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
            return 0L;
        }
    }

    /**
     * {@code Thread.start}'s exit, normal or not, with the token its entry returned: a started thread is recorded, and
     * tracked when the application's code started it for a request. Never throws.
     */
    public static void threadStarted(long token, Object thread, int hook, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            Claim claim = AgentBridge.current();
            if (thrown != null || !SideEffects.recording(claim, SideEffects.MASK_THREADS)) {
                return;
            }
            Thread started = (Thread) thread;
            boolean virtual = ThreadPropagation.isVirtual(started);
            String name = started.getName();
            boolean singleton = jdkSingleton(started, name);
            long stamp = CodePaths.stamp();
            SideEffects.Owner owner = owner(frame, claim);
            boolean owned = owner.request != 0L || owner.execution != 0L;
            State state = state(claim.generation);
            int target = nameTarget(state, name);
            int detail = virtual ? DETAIL_VIRTUAL : 0;
            long frames;
            int origin;
            boolean isStatic;
            Sighting sighting = null;
            boolean remembered = false;
            if (singleton) {
                JDK_SINGLETONS.increment();
                frames = 0L;
                origin = ORIGIN_JDK;
                isStatic = true;
            } else if (owned) {
                // A request's start always walks: its origin decides whether it is tracked, and a cached walk keyed by
                // a call site the code-paths sensor did not stamp could be another caller's.
                long[] walked = walk(claim, false);
                if (walked[1] == WORKER) {
                    WORKERS.increment();
                    return;
                }
                frames = walked[0];
                origin = (int) walked[1];
                isStatic = walked[2] != 0L;
            } else {
                Key probe = frame == null ? null : frame.threadProbe;
                if (probe == null) {
                    probe = new Key(0, 0, 0, 0L, 0);
                    if (frame != null) {
                        frame.threadProbe = probe;
                    }
                }
                probe.set(
                        owner.threadName,
                        target,
                        detail,
                        SideEffects.site(stamp),
                        started.getClass().hashCode());
                sighting = state.sightings.get(probe);
                if (sighting == null) {
                    long[] walked = walk(claim, false);
                    sighting = new Sighting(walked[0], (int) walked[1], walked[2] != 0L);
                    if (state.sightings.size() < MAX_SIGHTINGS) {
                        Key key = new Key(probe.family, probe.target, probe.detail, probe.site, probe.type);
                        Sighting raced = state.sightings.putIfAbsent(key, sighting);
                        if (raced != null) {
                            sighting = raced;
                        }
                        remembered = true;
                    } else {
                        SIGHTINGS_FULL.increment();
                    }
                } else {
                    remembered = true;
                }
                if (sighting.origin == WORKER) {
                    WORKERS.increment();
                    return;
                }
                frames = sighting.frames;
                origin = sighting.origin;
                isStatic = sighting.isStatic;
            }
            SideEffects.RECORDED[hook].increment();
            detail |= origin;
            if (isStatic) {
                detail |= DETAIL_STATIC;
            }
            if (owned) {
                SideEffects.record(
                        frame,
                        owner,
                        SideEffects.SENSOR_THREADS,
                        SideEffects.KIND_THREAD_START,
                        target,
                        SideEffects.OUTCOME_STARTED,
                        detail,
                        stamp,
                        frames,
                        0L);
                if (origin == ORIGIN_APPLICATION && !isStatic && owner.request != 0L) {
                    track(started, true, owner, target, stamp, frames, detail, claim);
                }
            } else if (remembered) {
                // Counted in its sighting, which the drain thread publishes.
                long now = System.currentTimeMillis();
                if (sighting.firstMillis == 0L) {
                    sighting.firstMillis = now;
                }
                sighting.lastMillis = now;
                sighting.pending.increment();
            } else {
                SideEffects.publishOne(
                        owner,
                        SideEffects.SENSOR_THREADS,
                        SideEffects.KIND_THREAD_START,
                        target,
                        SideEffects.OUTCOME_STARTED,
                        detail,
                        stamp,
                        frames,
                        0L,
                        System.currentTimeMillis());
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        } finally {
            close(frame);
        }
    }

    /**
     * {@code ThreadPoolExecutor.addWorker} or {@code ThreadPerTaskExecutor.start(Thread)} entry: the threads the pool
     * starts meanwhile are its own. Returns whether it marked the thread, for {@link #poolStarted}. Never throws.
     */
    public static boolean poolStarting(int hook) {
        if ((SideEffects.gate & SideEffects.MASK_THREADS) == 0) {
            return false;
        }
        try {
            if (Thread.currentThread() == SideEffects.selfTestThread) {
                SideEffects.SELF_TEST_HITS[hook].increment();
                return false;
            }
            CodePaths.frame().poolStarts++;
            return true;
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
            return false;
        }
    }

    /** The pool start {@link #poolStarting} marked ended. Never throws. */
    public static void poolStarted(boolean marked) {
        if (!marked) {
            return;
        }
        try {
            CodePaths.Frame frame = CodePaths.FRAME.get();
            if (frame != null && frame.poolStarts > 0) {
                frame.poolStarts--;
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        }
    }

    /**
     * An executor's canonical constructor returned, {@code hook} saying which: the executor is recorded, and tracked
     * when the application's code created it for a request or an execution. Reads only its class and identity, as its
     * subclass's constructor may still be running. Never throws.
     */
    public static void executorCreated(Object executor, int hook) {
        if ((SideEffects.gate & SideEffects.MASK_THREADS) == 0 || executor == null) {
            return;
        }
        CodePaths.Frame frame = null;
        long token = 0L;
        try {
            Thread current = Thread.currentThread();
            if (current == SideEffects.selfTestThread) {
                SideEffects.SELF_TEST_HITS[hook].increment();
                return;
            }
            if ((SideEffects.mask & SideEffects.MASK_THREADS) == 0
                    || Reentrancy.sideEffectsSkipped()
                    || current.getName().startsWith("bootui-")) {
                return;
            }
            frame = CodePaths.frame();
            token = open(frame);
            if (token == 0L) {
                return;
            }
            Claim claim = AgentBridge.current();
            if (!SideEffects.recording(claim, SideEffects.MASK_THREADS)) {
                return;
            }
            SideEffects.RECORDED[hook].increment();
            long[] walked = walk(claim, true);
            int origin = (int) walked[1];
            boolean isStatic = walked[2] != 0L;
            long stamp = CodePaths.stamp();
            SideEffects.Owner owner = owner(frame, claim);
            State state = state(claim.generation);
            String type = executor.getClass().getName();
            int target = target(state, executorName(type));
            int detail = origin | (executorKind(executor) << 4) | (isStatic ? DETAIL_STATIC : 0);
            SideEffects.record(
                    frame,
                    owner,
                    SideEffects.SENSOR_THREADS,
                    SideEffects.KIND_EXECUTOR_CREATE,
                    target,
                    SideEffects.OUTCOME_STARTED,
                    detail,
                    stamp,
                    walked[0],
                    0L);
            if (origin == ORIGIN_APPLICATION && !isStatic && (owner.request != 0L || owner.execution != 0L)) {
                track(executor, false, owner, target, stamp, walked[0], detail, claim);
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        } finally {
            if (token != 0L) {
                close(frame);
            }
        }
    }

    /**
     * An executor's {@code shutdown}, {@code shutdownNow}, or {@code close} entry, {@code hook} saying which: a tracked
     * executor's shutdown is recorded on its creation's row, as reclaimed when a cleaner or a finalizer shuts it down
     * because nothing references it. Never throws.
     */
    public static void executorShuttingDown(Object executor, int hook) {
        if ((SideEffects.gate & SideEffects.MASK_THREADS) == 0 || executor == null) {
            return;
        }
        CodePaths.Frame frame = null;
        long token = 0L;
        try {
            Thread current = Thread.currentThread();
            if (current == SideEffects.selfTestThread) {
                SideEffects.SELF_TEST_HITS[hook].increment();
                return;
            }
            if ((SideEffects.mask & SideEffects.MASK_THREADS) == 0 || TRACKER.size() == 0) {
                return;
            }
            frame = CodePaths.frame();
            token = open(frame);
            Claim claim = AgentBridge.current();
            if (claim == null || claim.generation != SideEffects.generation) {
                return;
            }
            List<ThreadTracker.Entry> reports = new ArrayList<ThreadTracker.Entry>(1);
            TRACKER.shutdown(executor, cleaner(current), claim.generation, reports);
            if (!reports.isEmpty()) {
                SideEffects.RECORDED[hook].increment();
                publish(reports);
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        } finally {
            if (token != 0L) {
                close(frame);
            }
        }
    }

    /**
     * A request of claim generation {@code requested} ended, its response complete, as the engine hears it from an
     * adapter: noted without a lock, for the drain thread to check what it left running. Never throws.
     */
    public static void requestEnded(long requested, long request) {
        try {
            if ((SideEffects.mask & SideEffects.MASK_THREADS) == 0
                    || requested != SideEffects.generation
                    || request == 0L) {
                return;
            }
            TRACKER.ended(request);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        }
    }

    /**
     * On the drain thread, before it drains: the request ends at least {@value #GRACE_NANOS} ns old checked, the
     * executors the collector reclaimed reported, and the unowned starts counted since the last sweep published. Never
     * throws.
     */
    static void sweep(Claim claim) {
        try {
            if (claim == null || claim.generation != SideEffects.generation) {
                return;
            }
            State state = STATE.get();
            if ((SideEffects.mask & SideEffects.MASK_THREADS) == 0 && TRACKER.size() == 0 && state == null) {
                return;
            }
            List<ThreadTracker.Entry> reports = new ArrayList<ThreadTracker.Entry>();
            TRACKER.processEnds(claim.generation, System.nanoTime(), GRACE_NANOS, reports);
            TRACKER.expunge(reports);
            publish(reports);
            if (state != null && state.generation == claim.generation) {
                for (Map.Entry<Key, Sighting> entry : state.sightings.entrySet()) {
                    Sighting sighting = entry.getValue();
                    long count = sighting.pending.sumThenReset();
                    if (count > 0) {
                        publishUnowned(entry.getKey(), sighting, count, claim.generation);
                    }
                }
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREADS, ex);
        }
    }

    // ---- recording ----------------------------------------------------------------------------------------------

    /** Opens the sensor's hook on {@code frame}: a token, 0 when another side-effect hook is open on it. */
    private static long open(CodePaths.Frame frame) {
        long now = System.nanoTime();
        int open = frame.sideEffectOpen;
        if (open != 0 && now - frame.sideEffectSince >= SideEffects.STALE_DEPTH_NANOS) {
            SideEffects.staleDepth();
            open = 0;
        }
        if ((open & SideEffects.silencedBy(SideEffects.MASK_THREADS)) != 0) {
            return 0L;
        }
        if (open == 0) {
            frame.sideEffectSince = now;
        }
        frame.sideEffectOpen = open | SideEffects.MASK_THREADS;
        return now == 0L ? 1L : now;
    }

    private static void close(CodePaths.Frame frame) {
        if (frame != null) {
            frame.sideEffectOpen &= ~SideEffects.MASK_THREADS;
        }
    }

    /**
     * The owner, as the network sensor reads it: the slot's, else captured unless on an event loop; the family named.
     * Reuses the thread's own {@code Owner} ({@code CodePaths.Frame#threadOwner}): the hooks never nest on a thread, and
     * nothing keeps it past the hook.
     */
    private static SideEffects.Owner owner(CodePaths.Frame frame, Claim claim) {
        int family = SideEffects.threadFamilyId(frame, claim.generation);
        SideEffects.Owner owner;
        if (frame == null) {
            owner = new SideEffects.Owner();
        } else {
            owner = frame.threadOwner;
            if (owner == null) {
                owner = new SideEffects.Owner();
                frame.threadOwner = owner;
            }
        }
        SideEffects.owner(owner, frame, claim, false, frame == null || !frame.sideEffectEventLoop);
        owner.threadName = family;
        return owner;
    }

    /**
     * The target of a started thread's family, by its name. Only a name that is its own family, which recurs, is
     * remembered: a name carrying an id, as {@code Thread-7}, is unique, so remembering it would only fill the cache.
     */
    static int nameTarget(State state, String name) {
        Integer known = state.names.get(name);
        if (known != null) {
            return known.intValue();
        }
        String family = name.isEmpty() ? UNNAMED : SideEffects.threadFamily(name);
        int target = target(state, family);
        if (family.equals(name) && state.names.size() < MAX_SIGHTINGS) {
            state.names.putIfAbsent(name, Integer.valueOf(target));
        }
        return target;
    }

    private static void track(
            Object referent,
            boolean thread,
            SideEffects.Owner owner,
            int target,
            long stamp,
            long frames,
            int detail,
            Claim claim) {
        List<ThreadTracker.Entry> reports = new ArrayList<ThreadTracker.Entry>(0);
        boolean tracked = TRACKER.track(
                referent,
                thread,
                claim.generation,
                owner.request,
                owner.execution,
                owner.executionKind,
                owner.threadKind,
                owner.threadName,
                target,
                stamp,
                frames,
                detail,
                System.currentTimeMillis(),
                reports);
        if (tracked) {
            TRACKED.increment();
        }
        publish(reports);
    }

    /** Publishes what the tracker reported, each on its creation's row. */
    private static void publish(List<ThreadTracker.Entry> reports) {
        for (int i = 0; i < reports.size(); i++) {
            ThreadTracker.Entry entry = reports.get(i);
            int kind;
            switch (entry.reported) {
                case ThreadTracker.THREAD_LEFT_RUNNING:
                    kind = SideEffects.KIND_THREAD_LEFT_RUNNING;
                    LEFT_RUNNING.increment();
                    break;
                case ThreadTracker.EXECUTOR_LEFT_RUNNING:
                    kind = SideEffects.KIND_EXECUTOR_LEFT_RUNNING;
                    LEFT_RUNNING.increment();
                    break;
                case ThreadTracker.EXECUTOR_SHUT_DOWN:
                    kind = SideEffects.KIND_EXECUTOR_SHUTDOWN;
                    SHUT_DOWN.increment();
                    break;
                default:
                    kind = SideEffects.KIND_EXECUTOR_RECLAIMED;
                    RECLAIMED.increment();
                    break;
            }
            long nanos = Math.max(0L, entry.reportedNanos - entry.createdNanos);
            long[] values = new long[SideEffects.RECORD];
            values[SideEffects.R_SENSOR] = SideEffects.SENSOR_THREADS;
            values[SideEffects.R_KIND] = kind;
            values[SideEffects.R_GENERATION] = entry.generation;
            // The creation's time: the engine attributes a follow-up as it attributed its creation.
            values[SideEffects.R_FIRST_MILLIS] = entry.createdMillis;
            values[SideEffects.R_LAST_MILLIS] = System.currentTimeMillis();
            values[SideEffects.R_REQUEST] = entry.request;
            values[SideEffects.R_EXECUTION] = entry.execution;
            values[SideEffects.R_STAMP] = entry.stamp;
            values[SideEffects.R_TARGET] = entry.target;
            values[SideEffects.R_FLAGS] = (SideEffects.OUTCOME_DONE & 0xFFL)
                    | ((long) (entry.threadKind & 0xF) << 8)
                    | ((long) (entry.executionKind & 0xF) << 12)
                    | ((long) (entry.threadName & 0xFFFF) << 16)
                    | ((long) entry.detail << 32);
            values[SideEffects.R_COUNT] = 1L;
            values[SideEffects.R_NANOS] = nanos;
            values[SideEffects.R_MAX_NANOS] = nanos;
            values[SideEffects.R_FRAMES] = entry.frames;
            SideEffects.publish(SideEffects.SENSOR_THREADS, values);
        }
    }

    /** Publishes the starts no request owns that {@code sighting} counted since the last sweep. */
    private static void publishUnowned(Key key, Sighting sighting, long count, long current) {
        long first = sighting.firstMillis;
        long last = sighting.lastMillis;
        sighting.firstMillis = 0L;
        long[] values = new long[SideEffects.RECORD];
        values[SideEffects.R_SENSOR] = SideEffects.SENSOR_THREADS;
        values[SideEffects.R_KIND] = SideEffects.KIND_THREAD_START;
        values[SideEffects.R_GENERATION] = current;
        values[SideEffects.R_FIRST_MILLIS] = first == 0L ? last : first;
        values[SideEffects.R_LAST_MILLIS] = last;
        values[SideEffects.R_STAMP] = 0L;
        values[SideEffects.R_TARGET] = key.target;
        int detail = key.detail | sighting.origin | (sighting.isStatic ? DETAIL_STATIC : 0);
        values[SideEffects.R_FLAGS] = (SideEffects.OUTCOME_STARTED & 0xFFL)
                | ((long) (SideEffects.THREAD_PLATFORM & 0xF) << 8)
                | ((long) (key.family & 0xFFFF) << 16)
                | ((long) detail << 32);
        values[SideEffects.R_COUNT] = count;
        values[SideEffects.R_FRAMES] = sighting.frames;
        SideEffects.publish(SideEffects.SENSOR_THREADS, values);
    }

    private static State state(long current) {
        while (true) {
            State state = STATE.get();
            if (state != null && state.generation == current) {
                return state;
            }
            if (state != null && state.generation > current) {
                return new State(current);
            }
            State fresh = new State(current);
            if (STATE.compareAndSet(state, fresh)) {
                return fresh;
            }
        }
    }

    /** A target's interned id: at most {@value #MAX_TARGETS} distinct ones a generation, the others as one. */
    private static int target(State state, String text) {
        Integer known = state.targets.get(text);
        if (known != null) {
            return known.intValue();
        }
        if (state.targets.size() >= MAX_TARGETS) {
            TARGETS_OVERFLOW.increment();
            return SideEffects.intern(OTHER_THREADS);
        }
        int id = SideEffects.intern(text);
        if (id == 0) {
            TARGETS_OVERFLOW.increment();
            return SideEffects.intern(OTHER_THREADS);
        }
        state.targets.putIfAbsent(text, Integer.valueOf(id));
        return id;
    }

    // ---- classification -----------------------------------------------------------------------------------------

    /** A pool's own worker, which the pool's executor stands for: never recorded, never walked. */
    static boolean worker(Thread thread) {
        if (thread instanceof ForkJoinWorkerThread || thread.getName().startsWith("bootui-")) {
            return true;
        }
        return "java.util.concurrent.DelayScheduler".equals(thread.getClass().getName());
    }

    /** A thread the JDK starts lazily once for the JVM: an innocuous thread or one of its named singletons. */
    static boolean jdkSingleton(Thread thread, String name) {
        if ("jdk.internal.misc.InnocuousThread".equals(thread.getClass().getName())) {
            return true;
        }
        for (String singleton : JDK_THREADS) {
            if (name.equals(singleton)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code thread} is the JDK's cleaner or finalizer, which shuts down an executor nothing references. */
    static boolean cleaner(Thread thread) {
        String name = thread.getName();
        return "Finalizer".equals(name)
                || "Common-Cleaner".equals(name)
                || "jdk.internal.misc.InnocuousThread".equals(thread.getClass().getName());
    }

    /** An executor's kind, bits 4–7 of the detail. */
    static int executorKind(Object executor) {
        if (executor instanceof java.util.concurrent.ScheduledThreadPoolExecutor) {
            return EXECUTOR_SCHEDULED;
        }
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
            return EXECUTOR_THREAD_POOL;
        }
        if (executor instanceof java.util.concurrent.ForkJoinPool) {
            return EXECUTOR_FORK_JOIN;
        }
        return EXECUTOR_PER_TASK;
    }

    /** An executor class's name, a hidden class's suffix dropped. */
    static String executorName(String type) {
        int hidden = type.indexOf("/0x");
        String name = hidden >= 0 ? type.substring(0, hidden) : type;
        return name.length() > SideEffects.MAX_TARGET ? name.substring(0, SideEffects.MAX_TARGET) : name;
    }

    /** The threading API's own frames, skipped to find the creator: a thread's or an executor's. */
    static boolean api(String className, boolean executor) {
        if (className.startsWith("kotlin.concurrent.")) {
            return true;
        }
        if (!executor) {
            return className.startsWith("java.lang.Thread")
                    || className.startsWith("java.lang.VirtualThread")
                    || className.startsWith("java.util.Timer");
        }
        return className.startsWith("java.util.concurrent.ThreadPoolExecutor")
                || className.startsWith("java.util.concurrent.ScheduledThreadPoolExecutor")
                || className.startsWith("java.util.concurrent.ForkJoinPool")
                || className.startsWith("java.util.concurrent.ThreadPerTaskExecutor")
                || className.startsWith("java.util.concurrent.Executors");
    }

    /**
     * Whether a frame creates something once for the application's life: a static initializer, or a container creating
     * a singleton bean on first use, which can happen inside a request, and again after a Quarkus live reload: Spring's
     * {@code DefaultSingletonBeanRegistry.getSingleton} (a lazy singleton, an {@code ObjectProvider} lookup) and ArC's
     * {@code AbstractSharedContext} ({@code @ApplicationScoped} and {@code @Singleton}). Request and prototype scopes
     * create through neither, so what their beans start stays tracked.
     */
    static boolean singletonCreation(String className, String method) {
        return "<clinit>".equals(method)
                || "getSingleton".equals(method)
                        && "org.springframework.beans.factory.support.DefaultSingletonBeanRegistry".equals(className)
                || className.startsWith("io.quarkus.arc.impl.AbstractSharedContext");
    }

    /** The JDK's own classes. */
    static boolean jdk(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.");
    }

    /** Walks for the creator: packed frames, the origin or {@link #WORKER}, and whether static. */
    private static long[] walk(Claim claim, boolean executor) {
        WALKS.increment();
        long[] found = WALKER.walk(new CreatorWalk(claim, executor));
        return found == null ? new long[] {0L, ORIGIN_LIBRARY, 0L} : found;
    }

    /**
     * Walks at most {@value #MAX_FRAMES} frames: past the agent's and the threading API's own, the immediate creator;
     * then the first frame outside the JDK, which decides the origin unless the creator is the JDK's, and the first in
     * the claimed packages, for the call site.
     */
    static final class CreatorWalk implements Function<Stream<StackWalker.StackFrame>, long[]> {

        private final Claim claim;
        private final boolean executor;

        CreatorWalk(Claim claim, boolean executor) {
            this.claim = claim;
            this.executor = executor;
        }

        @Override
        public long[] apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            boolean creatorFound = false;
            boolean creatorJdk = false;
            boolean isStatic = false;
            int outside = 0;
            int application = 0;
            boolean outsideApplication = false;
            for (int i = 0; i < MAX_FRAMES && iterator.hasNext() && !isStatic; i++) {
                StackWalker.StackFrame frame = iterator.next();
                String className = frame.getClassName();
                if (className.startsWith("io.github.jdubois.bootui.agent.")) {
                    continue;
                }
                String method = frame.getMethodName();
                if (application != 0) {
                    // The frames found; a static initializer or a container's singleton creation further down, as a
                    // lazy holder's or a lazy bean's first use inside a request, still makes it a singleton, never
                    // tracked.
                    isStatic = singletonCreation(className, method);
                    continue;
                }
                if (!creatorFound) {
                    if (api(className, executor)) {
                        continue;
                    }
                    creatorFound = true;
                    creatorJdk = jdk(className);
                    if (!executor && creatorJdk && className.startsWith("java.util.concurrent.")) {
                        return new long[] {0L, WORKER, 0L};
                    }
                }
                if (singletonCreation(className, method)) {
                    isStatic = true;
                }
                if (jdk(className)) {
                    continue;
                }
                boolean inPackages = claim != null && ThreadPropagation.inPackages(className, claim);
                if (outside == 0) {
                    outside = SideEffects.internFrame(className, method);
                    outsideApplication = inPackages;
                    if (outside == 0) {
                        // The frames' room is full: the origin still holds.
                        outside = -1;
                    }
                }
                if (inPackages) {
                    application = SideEffects.internFrame(className, method);
                    if (application == 0) {
                        break;
                    }
                }
            }
            int origin =
                    creatorJdk || outside == 0 ? ORIGIN_JDK : outsideApplication ? ORIGIN_APPLICATION : ORIGIN_LIBRARY;
            long packed = ((long) Math.max(0, outside) << 32) | (application & 0xFFFFFFFFL);
            return new long[] {packed, origin, isStatic ? 1L : 0L};
        }
    }

    // ---- lifecycle and status -----------------------------------------------------------------------------------

    /** Loads and links what the hooks use, on the agent's own thread, before any is installed. */
    static void warm() {
        TRACKER.size();
        TRACKER.anyWaiting();
        Thread self = Thread.currentThread();
        worker(self);
        jdkSingleton(self, self.getName());
        cleaner(self);
        executorKind(new Object());
        executorName("warm/0x1");
        api("warm", true);
        api("warm", false);
        jdk("warm");
        WALKER.walk(new CreatorWalk(null, false)).getClass();
        State state = new State(-1L);
        Key key = new Key(0, 0, 0, 0L, 0);
        state.sightings.putIfAbsent(key, new Sighting(0L, 0, false));
        state.sightings.get(key).pending.sumThenReset();
        state.targets.putIfAbsent("warm", Integer.valueOf(0));
        new ArrayList<ThreadTracker.Entry>(0).size();
        new ThreadTracker.Entry(null, null, false, 0L, 0L, 0L, 0, 0, 0, 0, 0L, 0L, 0, 0L, 0L)
                .report(0, 0L)
                .getClass();
    }

    /** The sensor was disabled or released: nothing is kept past its life. */
    static void disabled() {
        TRACKER.clear();
        STATE.set(null);
    }

    static void putStatus(Map<String, Object> map) {
        map.put("tracked", Long.valueOf(TRACKED.sum()));
        map.put("tracking", Integer.valueOf(TRACKER.size()));
        map.put("waitingForRequestEnd", Integer.valueOf(TRACKER.waitingCount()));
        map.put("untracked", Long.valueOf(TRACKER.untracked.sum()));
        map.put("unresolved", Long.valueOf(TRACKER.unresolved.sum()));
        map.put("startedAfterRequestEnd", Long.valueOf(TRACKER.afterEnd.sum()));
        map.put("requestEndsChecked", Long.valueOf(TRACKER.endsChecked.sum()));
        map.put("requestEndsLost", Long.valueOf(TRACKER.endsLost.sum()));
        map.put("dropped", Long.valueOf(TRACKER.dropped.sum()));
        map.put("leftRunning", Long.valueOf(LEFT_RUNNING.sum()));
        map.put("shutDown", Long.valueOf(SHUT_DOWN.sum()));
        map.put("reclaimed", Long.valueOf(RECLAIMED.sum()));
        map.put("poolWorkersSkipped", Long.valueOf(WORKERS.sum()));
        map.put("jdkSingletons", Long.valueOf(JDK_SINGLETONS.sum()));
        map.put("walks", Long.valueOf(WALKS.sum()));
        State state = STATE.get();
        map.put("sightings", Integer.valueOf(state == null ? 0 : state.sightings.size()));
        map.put("sightingsFull", Long.valueOf(SIGHTINGS_FULL.sum()));
        map.put("targetsOverflow", Long.valueOf(TARGETS_OVERFLOW.sum()));
    }

    /** Tests only: forgets everything. */
    static void reset() {
        TRACKER.clear();
        STATE.set(null);
        WORKERS.reset();
        JDK_SINGLETONS.reset();
        WALKS.reset();
        SIGHTINGS_FULL.reset();
        TARGETS_OVERFLOW.reset();
        TRACKED.reset();
        LEFT_RUNNING.reset();
        RECLAIMED.reset();
        SHUT_DOWN.reset();
        TRACKER.untracked.reset();
        TRACKER.unresolved.reset();
        TRACKER.afterEnd.reset();
        TRACKER.endsLost.reset();
        TRACKER.endsChecked.reset();
        TRACKER.dropped.reset();
    }
}
