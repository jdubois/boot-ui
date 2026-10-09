package io.github.jdubois.bootui.agent.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code threads} sensor's entry points (PLAN-v2 M5-2c, D32): a thread started from owned work is keyed, by its own
 * identity, to the starting thread's snapshot when it starts, and the snapshot is reopened where the thread runs its
 * task. A thread, platform or virtual, is keyed only when application code starts it: the first caller of
 * {@code start()} outside the JDK must belong to the claimed packages, so a thread a library starts inside a request
 * (a pool filler, a client's I/O thread, an asynchronous appender, a framework's virtual-thread dispatch, a
 * context-propagating executor wrapper) never is, even when its task is an application lambda. A pool worker's thread
 * is never keyed: a thread started inside {@code ThreadPoolExecutor.addWorker} or a fork/join worker would otherwise
 * carry its creator's request for its whole life. Nor is a platform thread whose class overrides {@code run()} outside
 * the claimed packages, such as {@code java.util.Timer}'s, since no instrumented run point would ever take its key.
 * Same snapshot contract and rules as {@link TaskPropagation}.
 */
public final class ThreadPropagation {

    public static final String SENSOR = "threads";

    public static final int KEY_THREAD_START = 0;
    public static final int KEY_VIRTUAL_START = 1;

    public static final int APPLY_THREAD_RUN = 0;
    public static final int APPLY_SUBCLASS_RUN = 1;
    /** The same run point as {@link #APPLY_THREAD_RUN}, reached by a virtual thread: counted apart, tested apart. */
    public static final int APPLY_VIRTUAL_RUN = 2;

    static final String[] KEY_HOOKS = {"Thread.start", "VirtualThread.start"};
    static final String[] APPLY_HOOKS = {"Thread.run", "Thread subclass run", "VirtualThread.run"};

    /**
     * Threads inside {@code ThreadPoolExecutor.addWorker}, with their nesting depth (a thread factory may itself add a
     * worker to another pool): the threads they start are pool workers.
     */
    private static final ConcurrentHashMap<Thread, int[]> ADDING_WORKER = new ConcurrentHashMap<Thread, int[]>();

    /** Whether a {@code Thread} subclass overrides {@code run()}, so {@code Thread.run} never runs for it. */
    private static final ClassValue<Boolean> OVERRIDES_RUN = new OverridesRun();

    private static final StackWalker WALKER = StackWalker.getInstance();

    /** No generation is disabled. */
    private static final long NONE = Long.MIN_VALUE;

    /** Every generation is disabled: the sensor failed its self-test and could not remove its transformer. */
    private static final long ALL = Long.MAX_VALUE;

    private static volatile long disabledGeneration = NONE;

    private static volatile Thread selfTestThread;

    private static final LongAdder[] KEYED = adders(KEY_HOOKS.length);
    private static final LongAdder[] APPLIED = adders(APPLY_HOOKS.length);
    private static final LongAdder[] SELF_TEST_KEYED = adders(KEY_HOOKS.length);
    private static final LongAdder[] SELF_TEST_APPLIED = adders(APPLY_HOOKS.length);
    private static final LongAdder AMBIGUOUS = new LongAdder();
    private static final LongAdder STALE = new LongAdder();
    private static final LongAdder REFUSED = new LongAdder();
    private static final LongAdder SKIPPED_TASKS = new LongAdder();
    private static final LongAdder SKIPPED_THREADS = new LongAdder();
    private static final LongAdder LIBRARY_THREADS = new LongAdder();
    private static final LongAdder POOL_WORKERS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private ThreadPropagation() {}

    // ---- key points ----------------------------------------------------------------------------------------------

    /** {@code ThreadPoolExecutor.addWorker} entry and exit: threads started meanwhile are the pool's workers. */
    public static void addingWorker(boolean adding) {
        try {
            Thread current = Thread.currentThread();
            if (adding) {
                if (sensorOn() || current == selfTestThread) {
                    int[] depth = ADDING_WORKER.get(current);
                    if (depth == null) {
                        ADDING_WORKER.put(current, new int[] {1});
                    } else {
                        depth[0]++;
                    }
                }
            } else if (!ADDING_WORKER.isEmpty()) {
                int[] depth = ADDING_WORKER.get(current);
                if (depth != null && --depth[0] <= 0) {
                    ADDING_WORKER.remove(current);
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * {@code Thread.start} entry (and {@code VirtualThread.start(ThreadContainer)}): keys {@code thread} before it can
     * run. Returns whether an entry was touched, so a failed start must {@link #started} release it.
     */
    public static boolean starting(Object thread, int hook) {
        try {
            if (!(thread instanceof Thread)) {
                return false;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || disabled(claim.generation)) {
                return false;
            }
            long generation = claim.generation;
            Thread started = (Thread) thread;
            Thread current = Thread.currentThread();
            boolean selfTest = current == selfTestThread;
            if (!selfTest && !claim.hasSensor(SENSOR)) {
                return false;
            }
            // Before the self-test's marker: a virtual thread's first start also starts its carriers.
            if (thread instanceof ForkJoinWorkerThread
                    || (!ADDING_WORKER.isEmpty() && ADDING_WORKER.containsKey(current))) {
                if (!selfTest && !bootUi(started) && !bootUi(current)) {
                    POOL_WORKERS.increment();
                }
                return false;
            }
            boolean virtual = isVirtual(started);
            if (!virtual && overridesRunOutside(started, claim)) {
                return false;
            }
            if (selfTest) {
                SELF_TEST_KEYED[hook].increment();
                TaskSnapshots.THREADS.putSelfTest(thread, generation, TaskPropagation.SELF_TEST);
                return true;
            }
            if (bootUi(started)) {
                return false;
            }
            Supplier<Object> capture = claim.capture.get();
            Object payload = capture == null ? null : capture.get();
            if (payload == null) {
                return false;
            }
            if (!TaskPropagation.flatJdkValues(payload)) {
                REFUSED.increment();
                return false;
            }
            // Walked only inside owned work, where a start is already far costlier than the walk.
            if (!startedByApplication(claim)) {
                LIBRARY_THREADS.increment();
                return false;
            }
            int put =
                    TaskSnapshots.THREADS.put(thread, generation, (Object[]) payload, Math.max(0L, CodePaths.stamp()));
            if (put == TaskSnapshots.REFUSED) {
                // The registry is full: the thread is not keyed and runs unowned.
                TaskSnapshots.THREADS.overflowed();
                return false;
            }
            KEYED[hook].increment();
            if (put == TaskSnapshots.AMBIGUOUS_PUT) {
                AMBIGUOUS.increment();
            }
            return true;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return false;
        }
    }

    /** {@code Thread.start} exit: a thread that failed to start never runs. */
    public static void started(boolean keyed, Object thread, Throwable thrown) {
        if (keyed && thrown != null) {
            try {
                TaskSnapshots.THREADS.release(thread);
            } catch (Throwable ex) {
                AgentBridge.error(ex);
            }
        }
    }

    /** Earlier-generation threads are never reopened, but stay retained to preserve cross-generation ambiguity. */
    static void claimed(long generation) {
        try {
            TaskSnapshots.THREADS.releaseEarlierClaims(generation);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    // ---- apply points --------------------------------------------------------------------------------------------

    /**
     * Substituted for {@code task.run()} in {@code Thread.run} (JDK 17) and {@code Thread.runWith} (JDK 21 and later,
     * which virtual threads also run through): reopens the snapshot keyed on the running thread, if any. A key only
     * lives from a thread's start to its first run point, so another thread's {@code run()} called directly finds none.
     */
    public static void runThreadTask(Runnable task) {
        Thread current = Thread.currentThread();
        Object handle = enter(current, task, isVirtual(current) ? APPLY_VIRTUAL_RUN : APPLY_THREAD_RUN);
        if (handle == null) {
            task.run();
            return;
        }
        Throwable thrown = null;
        try {
            task.run();
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            exit(handle, thrown);
        }
    }

    /** A claimed {@code Thread} subclass's own {@code run()} entry: reopens its snapshot when it runs on itself. */
    public static Object enterSubclass(Object thread) {
        if (thread != Thread.currentThread()) {
            return null;
        }
        return enter((Thread) thread, null, APPLY_SUBCLASS_RUN);
    }

    static Object enter(Thread thread, Runnable task, int hook) {
        try {
            if (TaskSnapshots.THREADS.isEmpty()) {
                return null;
            }
            Object entry = TaskSnapshots.THREADS.take(thread);
            if (entry == null || entry == TaskSnapshots.AMBIGUOUS) {
                return null;
            }
            TaskSnapshots.Entry snapshot = (TaskSnapshots.Entry) entry;
            if (snapshot.payload == TaskPropagation.SELF_TEST) {
                SELF_TEST_APPLIED[hook].increment();
                return null;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != snapshot.generation) {
                STALE.increment();
                return null;
            }
            if (disabled(claim.generation)) {
                return null;
            }
            String taskClass =
                    task == null ? thread.getClass().getName() : task.getClass().getName();
            if (Claim.startsWithAny(taskClass, claim.skipTasks)) {
                SKIPPED_TASKS.increment();
                return null;
            }
            if (Claim.startsWithAny(thread.getName(), claim.skipThreads)) {
                SKIPPED_THREADS.increment();
                return null;
            }
            Function<Object, AutoCloseable> reopen = claim.reopen.get();
            if (reopen == null) {
                return null;
            }
            Object handle = reopen.apply(new Object[] {snapshot.payload, taskClass, APPLY_HOOKS[hook]});
            if (handle != null) {
                APPLIED[hook].increment();
                // The thread's code-paths fragment records the node that started it (PLAN-v2 §5.14, design I7).
                CodePaths.handoff(snapshot.stamp);
                // The side-effect sensors' owner slot names the submitting request (PLAN-v2 M5-5 design B1).
                SideEffects.handoff(snapshot.payload, snapshot.generation);
            }
            return handle;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return null;
        }
    }

    /** Ends the child execution {@code handle} opened, telling it the thread's failure first, if any. */
    public static void exit(Object handle, Throwable failure) {
        if (handle != null && failure != null) {
            FAILURES.increment();
        }
        TaskPropagation.exitHandle(handle, failure);
    }

    static boolean isVirtual(Thread thread) {
        return thread.getClass().getName().indexOf("VirtualThread") >= 0;
    }

    private static boolean bootUi(Thread thread) {
        return thread.getName().startsWith("bootui-");
    }

    /** A platform thread whose class overrides {@code run()} outside the claimed packages: no hook would apply it. */
    static boolean overridesRunOutside(Thread thread, Claim claim) {
        Class<?> type = thread.getClass();
        return type != Thread.class
                && !inPackages(type.getName(), claim)
                && OVERRIDES_RUN.get(type).booleanValue();
    }

    /**
     * Whether the first caller of {@code start()} outside the JDK and this bridge belongs to the claimed packages. A
     * thread {@code CompletableFuture}'s own thread-per-task fallback starts is left to the executors sensor, whose
     * apply hook reads the stage's outcome.
     */
    static boolean startedByApplication(Claim claim) {
        return WALKER.walk(new FirstCaller(claim)).booleanValue();
    }

    /** The JDK, this bridge, and Kotlin's {@code thread { }}, which start a thread on their caller's behalf. */
    static boolean jdkOrBridge(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("io.github.jdubois.bootui.agent.bridge.")
                || className.startsWith("kotlin.concurrent.");
    }

    static final String COMPLETABLE_FUTURE_FALLBACK = "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor";

    /** The answer of the first frame outside the JDK and this bridge, within 64 frames. */
    static final class FirstCaller implements Function<java.util.stream.Stream<StackWalker.StackFrame>, Boolean> {
        private final Claim claim;

        FirstCaller(Claim claim) {
            this.claim = claim;
        }

        @Override
        public Boolean apply(java.util.stream.Stream<StackWalker.StackFrame> frames) {
            java.util.Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            for (int i = 0; i < 64 && iterator.hasNext(); i++) {
                String className = iterator.next().getClassName();
                if (COMPLETABLE_FUTURE_FALLBACK.equals(className)) {
                    return Boolean.FALSE;
                }
                if (!jdkOrBridge(className)) {
                    return Boolean.valueOf(inPackages(className, claim));
                }
            }
            return Boolean.FALSE;
        }
    }

    static final class OverridesRun extends ClassValue<Boolean> {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return Boolean.valueOf(type.getMethod("run").getDeclaringClass() != Thread.class);
            } catch (Throwable ex) {
                return Boolean.TRUE;
            }
        }
    }

    /**
     * Stops the sensor for {@code generation} whatever its advice still does, as when its self-test failed; with
     * {@code everyGeneration}, for good, as when it also could not remove its transformer.
     */
    public static void disable(long generation, boolean everyGeneration) {
        disabledGeneration = everyGeneration ? ALL : generation;
        TaskSnapshots.THREADS.reset();
        ADDING_WORKER.clear();
    }

    static boolean disabled(long generation) {
        long disabled = disabledGeneration;
        return disabled == ALL || disabled == generation;
    }

    static boolean inPackages(String className, Claim claim) {
        for (int i = 0; i < claim.packages.size(); i++) {
            String prefix = claim.packages.get(i);
            if (className.startsWith(prefix)
                    && className.length() > prefix.length()
                    && className.charAt(prefix.length()) == '.') {
                return true;
            }
        }
        return false;
    }

    private static boolean sensorOn() {
        Claim claim = AgentBridge.current();
        return claim != null && claim.armed && claim.hasSensor(SENSOR) && !disabled(claim.generation);
    }

    // ---- self-test and status ------------------------------------------------------------------------------------

    /** Starts the threads sensor's self-test on the calling thread: the threads it starts carry the self-test marker. */
    public static void beginSelfTest() {
        for (int i = 0; i < SELF_TEST_KEYED.length; i++) {
            SELF_TEST_KEYED[i].reset();
        }
        for (int i = 0; i < SELF_TEST_APPLIED.length; i++) {
            SELF_TEST_APPLIED[i].reset();
        }
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test: how many marker threads each key and apply point saw. */
    public static Map<String, Object> endSelfTest() {
        selfTestThread = null;
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("keyed", TaskPropagation.counts(KEY_HOOKS, SELF_TEST_KEYED));
        map.put("applied", TaskPropagation.counts(APPLY_HOOKS, SELF_TEST_APPLIED));
        return map;
    }

    static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("keyed", TaskPropagation.counts(KEY_HOOKS, KEYED));
        map.put("applied", TaskPropagation.counts(APPLY_HOOKS, APPLIED));
        map.put("pending", Long.valueOf(TaskSnapshots.THREADS.size()));
        map.put("neverApplied", Long.valueOf(TaskSnapshots.THREADS.neverApplied()));
        map.put("ambiguous", Long.valueOf(AMBIGUOUS.sum()));
        map.put("stale", Long.valueOf(STALE.sum()));
        map.put("refused", Long.valueOf(REFUSED.sum()));
        map.put("overflow", Long.valueOf(TaskSnapshots.THREADS.overflow()));
        map.put("skippedTasks", Long.valueOf(SKIPPED_TASKS.sum()));
        map.put("skippedThreads", Long.valueOf(SKIPPED_THREADS.sum()));
        map.put("libraryThreadsSkipped", Long.valueOf(LIBRARY_THREADS.sum()));
        map.put("poolWorkersSkipped", Long.valueOf(POOL_WORKERS.sum()));
        map.put("failures", Long.valueOf(FAILURES.sum()));
        // The generation the sensor failed in, -1 when none, or every generation: a runtime switch cannot turn it
        // back on in that run (PLAN-v2 M5-14).
        long disabled = disabledGeneration;
        map.put("disabledGeneration", Long.valueOf(disabled == NONE ? -1L : disabled));
        return map;
    }

    static void reset() {
        TaskSnapshots.THREADS.reset();
        ADDING_WORKER.clear();
        for (int i = 0; i < KEYED.length; i++) {
            KEYED[i].reset();
            SELF_TEST_KEYED[i].reset();
        }
        for (int i = 0; i < APPLIED.length; i++) {
            APPLIED[i].reset();
            SELF_TEST_APPLIED[i].reset();
        }
        AMBIGUOUS.reset();
        STALE.reset();
        REFUSED.reset();
        SKIPPED_TASKS.reset();
        SKIPPED_THREADS.reset();
        LIBRARY_THREADS.reset();
        POOL_WORKERS.reset();
        FAILURES.reset();
        selfTestThread = null;
        disabledGeneration = NONE;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }
}
