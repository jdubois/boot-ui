package io.github.jdubois.bootui.agent.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The executor sensor's entry points (PLAN-v2 M5-2, D32), called by the advice the agent adds to the JDK's executors.
 * Where an executor receives a task, the submitter's correlation snapshot is keyed to the task's identity
 * ({@link TaskSnapshots}); where the executor runs it, the snapshot is reopened as a child execution through the claim's
 * {@code reopen}. The task object is never replaced.
 *
 * <p>Every entry point checks the claim first (a volatile read), never throws, and calls into the engine only through
 * the claim's weakly held {@code capture} and {@code reopen}. Contract: {@code capture} returns {@code null} or a flat
 * {@code Object[]} of {@code String}, {@code Long}, {@code Integer}, {@code Boolean}, or {@code null}, whose first four
 * values identify the owner (request, execution, trace, and span ids); {@code reopen} receives
 * {@code Object[] {snapshot, taskClass, hook}} and returns {@code null} or an {@code AutoCloseable}, which may also be a
 * {@code Consumer<Throwable>} told the task's failure before it is closed. An optional {@code Runnable} receives the
 * body-completion marker before the JDK publishes its result; handoff closure still follows publication and tails.
 */
public final class TaskPropagation {

    public static final String SENSOR = "executors";

    /** Key points: where an executor receives a task. */
    public static final int KEY_THREAD_POOL = 0;

    public static final int KEY_SCHEDULED = 1;
    public static final int KEY_FORK_JOIN_ROOT = 2;
    public static final int KEY_FORK_JOIN_ADAPTER = 3;
    public static final int KEY_FORK = 4;
    public static final int KEY_DELAYED = 5;
    public static final int KEY_THREAD_PER_TASK = 6;

    /** Apply points: where an executor runs a task. */
    public static final int APPLY_RUN_WORKER = 0;

    public static final int APPLY_DO_EXEC = 1;
    public static final int APPLY_ASYNC = 2;

    static final String[] KEY_HOOKS = {
        "ThreadPoolExecutor",
        "ScheduledThreadPoolExecutor",
        "ForkJoinPool",
        "ForkJoinTask adapters",
        "ForkJoinTask.fork",
        "DelayScheduler",
        "CompletableFuture.ThreadPerTaskExecutor"
    };
    static final String[] APPLY_HOOKS = {
        "ThreadPoolExecutor.runWorker", "ForkJoinTask.doExec", "CompletableFuture.Async"
    };

    /** The self-test's snapshot: recognized by identity, never reachable through the engine's capture. */
    static final Object[] SELF_TEST = new Object[] {"bootui-agent-self-test"};

    private static volatile Thread selfTestThread;
    /** A claim generation whose self-test failed: no keying and no applying under it. */
    private static volatile long disabledGeneration = -1L;

    private static volatile String disabledReason;
    /** Whether {@code CompletableFuture}'s own advice applies its async tasks: set once that hook passed its self-test. */
    private static volatile boolean asyncApplies;

    private static final LongAdder[] KEYED = adders(KEY_HOOKS.length);
    private static final LongAdder[] APPLIED = adders(APPLY_HOOKS.length);
    private static final LongAdder[] SELF_TEST_KEYED = adders(KEY_HOOKS.length);
    private static final LongAdder[] SELF_TEST_APPLIED = adders(APPLY_HOOKS.length);
    private static final LongAdder AMBIGUOUS = new LongAdder();
    private static final LongAdder STALE = new LongAdder();
    private static final LongAdder REFUSED = new LongAdder();
    private static final LongAdder VIRTUAL_SKIPPED = new LongAdder();
    private static final LongAdder PERIODIC_SKIPPED = new LongAdder();
    private static final LongAdder SKIPPED_TASKS = new LongAdder();
    private static final LongAdder SKIPPED_THREADS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final BodyCaller BODY_CALLER = new BodyCaller();

    private TaskPropagation() {}

    private static final class Active {
        // Only the worker's dynamic scope holds these references; exit removes it even when closing fails.
        final Object target;
        final Object handle;
        final Active previous;
        boolean completed;
        boolean nestedPublication;

        Active(Object target, Object handle, Active previous) {
            this.target = target;
            this.handle = handle;
            this.previous = previous;
        }
    }

    // ---- key points ----------------------------------------------------------------------------------------------

    /**
     * Keys {@code task} to the calling thread's snapshot. Returns whether an entry was touched, so a failure path must
     * {@link #release} it. An unowned or refused submission of a task with a pending owned one makes that entry
     * ambiguous.
     */
    public static boolean submitted(Object task, int hook) {
        return submit(task, hook, true) != NONE;
    }

    static final int NONE = 0;
    static final int TOUCHED = 1;
    static final int KEYED_OWNED = 2;

    /**
     * Keys {@code task}; {@code count} says whether a keyed submission counts now, or only once the executor accepted it
     * ({@link #confirm}). Returns {@link #NONE}, {@link #TOUCHED} (an existing entry made ambiguous), or
     * {@link #KEYED_OWNED}.
     */
    static int submit(Object task, int hook, boolean count) {
        try {
            if (task == null) {
                return NONE;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed) {
                return NONE;
            }
            long generation = claim.generation;
            if (Thread.currentThread() == selfTestThread) {
                SELF_TEST_KEYED[hook].increment();
                TaskSnapshots.TASKS.put(task, generation, SELF_TEST);
                return TOUCHED;
            }
            if (!claim.hasSensor(SENSOR) || generation == disabledGeneration) {
                return NONE;
            }
            if (Claim.startsWithAny(task.getClass().getName(), claim.skipTasks)) {
                SKIPPED_TASKS.increment();
                return NONE;
            }
            Supplier<Object> capture = claim.capture.get();
            Object payload = capture == null ? null : capture.get();
            if (payload == null) {
                return TaskSnapshots.TASKS.putUnowned(task, generation) ? TOUCHED : NONE;
            }
            if (!flatJdkValues(payload)) {
                REFUSED.increment();
                return TaskSnapshots.TASKS.putUnowned(task, generation) ? TOUCHED : NONE;
            }
            if (count) {
                KEYED[hook].increment();
            }
            if (!TaskSnapshots.TASKS.put(task, generation, (Object[]) payload)) {
                AMBIGUOUS.increment();
            }
            return KEYED_OWNED;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return NONE;
        }
    }

    /** {@code ThreadPoolExecutor.addWorker(firstTask, core)} entry: keyed, counted once the worker started. */
    public static int workerOffered(Object firstTask) {
        return firstTask == null ? NONE : submit(firstTask, KEY_THREAD_POOL, false);
    }

    /** {@code ThreadPoolExecutor.addWorker} exit: counts a started worker's task, releases one that did not start. */
    public static void workerAdded(int outcome, Object firstTask, boolean started) {
        if (outcome == NONE) {
            return;
        }
        if (started) {
            confirm(outcome, KEY_THREAD_POOL);
        } else {
            release(firstTask);
        }
    }

    static void confirm(int outcome, int hook) {
        if (outcome == KEYED_OWNED) {
            KEYED[hook].increment();
        }
    }

    /** Substituted for {@code workQueue.offer(command)} in {@code ThreadPoolExecutor.execute}: keys what is queued. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean offer(BlockingQueue queue, Object task) {
        int outcome = submit(task, KEY_THREAD_POOL, false);
        boolean queued = false;
        try {
            queued = queue.offer(task);
            return queued;
        } finally {
            if (outcome != NONE) {
                if (queued) {
                    confirm(outcome, KEY_THREAD_POOL);
                } else {
                    release(task);
                }
            }
        }
    }

    /** {@code ScheduledThreadPoolExecutor.delayedExecute} exit: a task rejected because the pool shut down never runs. */
    public static void scheduledDone(Object task, Object executor, Throwable thrown) {
        try {
            if (thrown != null
                    || (executor instanceof java.util.concurrent.ExecutorService
                            && ((java.util.concurrent.ExecutorService) executor).isShutdown())) {
                release(task);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** {@code ScheduledThreadPoolExecutor.delayedExecute}: one-shot tasks only; a periodic task stays unowned. */
    public static void scheduled(Object task) {
        boolean periodic;
        try {
            periodic = task instanceof RunnableScheduledFuture && ((RunnableScheduledFuture<?>) task).isPeriodic();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return;
        }
        if (periodic) {
            PERIODIC_SKIPPED.increment();
        } else {
            submitted(task, KEY_SCHEDULED);
        }
    }

    /**
     * A root {@code ForkJoinTask} submitted to a {@code ForkJoinPool}. The pool's own adapters are keyed where they are
     * constructed, and virtual threads' continuations never.
     */
    public static void forkJoinRoot(Object pool, Object task) {
        if (!(task instanceof ForkJoinTask)) {
            return;
        }
        Thread current = Thread.currentThread();
        if (current instanceof ForkJoinWorkerThread && ((ForkJoinWorkerThread) current).getPool() == pool) {
            // A worker's own subtask, not a root submission.
            return;
        }
        String name = task.getClass().getName();
        if (name.startsWith("java.util.concurrent.ForkJoinTask$")
                || name.startsWith("java.util.concurrent.DelayScheduler$")) {
            return;
        }
        submitted(task, KEY_FORK_JOIN_ROOT);
    }

    /** Constructors of {@code ForkJoinTask$Adapted*} and {@code $RunnableExecuteAction}: skips virtual threads. */
    public static void adapterCreated(Object adapter, Object wrapped) {
        if (wrapped != null && wrapped.getClass().getName().startsWith("java.lang.VirtualThread")) {
            if (AgentBridge.recording()) {
                VIRTUAL_SKIPPED.increment();
            }
            return;
        }
        submitted(adapter, KEY_FORK_JOIN_ADAPTER);
    }

    /** {@code ForkJoinTask.fork()} from a thread that is not a pool worker: the task goes to the common pool. */
    public static void forked(Object task) {
        if (!(Thread.currentThread() instanceof ForkJoinWorkerThread)) {
            submitted(task, KEY_FORK);
        }
    }

    /** JDK 25+ {@code DelayScheduler$ScheduledForkJoinTask} constructors: one-shot when the period is 0. */
    public static void delayedCreated(Object task, long period) {
        if (period != 0L) {
            if (AgentBridge.recording()) {
                PERIODIC_SKIPPED.increment();
            }
            return;
        }
        submitted(task, KEY_DELAYED);
    }

    /** A submission that will not run: the worker did not start, or the task was removed. */
    public static void release(Object task) {
        try {
            TaskSnapshots.TASKS.release(task);
            TaskSnapshots.TASKS.expungeStale();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    // ---- apply points --------------------------------------------------------------------------------------------

    /** Where an executor runs {@code task}: the handle to {@link #exit} after it, or {@code null}. */
    public static Object enter(Object task, int hook) {
        return enter(task, hook, task);
    }

    /** Async stages publish to their dependent future rather than to the runnable itself. */
    public static Object enter(Object task, int hook, Object completionTarget) {
        try {
            if (task == null || TaskSnapshots.TASKS.isEmpty()) {
                return null;
            }
            if (hook != APPLY_ASYNC && asyncApplies && isAsyncTask(task)) {
                // CompletableFuture's own advice applies it, where the stage's outcome is readable.
                return null;
            }
            Object entry = TaskSnapshots.TASKS.take(task);
            if (entry == null || entry == TaskSnapshots.AMBIGUOUS) {
                return null;
            }
            if (task instanceof FutureTask && ((FutureTask<?>) task).isCancelled()) {
                return null;
            }
            TaskSnapshots.Entry snapshot = (TaskSnapshots.Entry) entry;
            if (snapshot.payload == SELF_TEST) {
                SELF_TEST_APPLIED[hook].increment();
                return null;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != snapshot.generation) {
                STALE.increment();
                return null;
            }
            if (claim.generation == disabledGeneration) {
                return null;
            }
            if (Claim.startsWithAny(Thread.currentThread().getName(), claim.skipThreads)) {
                SKIPPED_THREADS.increment();
                return null;
            }
            Function<Object, AutoCloseable> reopen = claim.reopen.get();
            if (reopen == null) {
                return null;
            }
            Object handle =
                    reopen.apply(new Object[] {snapshot.payload, task.getClass().getName(), APPLY_HOOKS[hook]});
            if (handle != null) {
                APPLIED[hook].increment();
                if (handle instanceof Runnable) {
                    Active active = new Active(completionTarget, handle, ACTIVE.get());
                    ACTIVE.set(active);
                    return active;
                }
            }
            return handle;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return null;
        }
    }

    /** Ends the child execution {@code handle} opened, telling it the task's failure first, if any. */
    public static void exit(Object handle, Throwable failure) {
        if (handle != null && failure != null) {
            FAILURES.increment();
        }
        if (handle instanceof Active) {
            Active active = (Active) handle;
            try {
                exitHandle(active.handle, failure);
            } finally {
                if (active.previous == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(active.previous);
                }
            }
        } else {
            exitHandle(handle, failure);
        }
    }

    /** Result-publication entry, before a waiter can resume. Never marks a different or nested task. */
    public static void bodyCompleted(Object target) {
        try {
            if (!AgentBridge.recording()) {
                return;
            }
            Active active = ACTIVE.get();
            if (active == null || active.completed) {
                return;
            }
            if (active.target != target) {
                // A decorator can hide a FutureTask that releases waiters before the decorator returns.
                active.nestedPublication = true;
                return;
            }
            if ((target instanceof ForkJoinTask && ((ForkJoinTask<?>) target).isDone()) || !jdkBodyReturned()) {
                return;
            }
            active.completed = true;
            ((Runnable) active.handle).run();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static boolean jdkBodyReturned() {
        return WALKER.walk(BODY_CALLER).booleanValue();
    }

    private static final class BodyCaller
            implements Function<java.util.stream.Stream<StackWalker.StackFrame>, Boolean> {

        @Override
        public Boolean apply(java.util.stream.Stream<StackWalker.StackFrame> frames) {
            java.util.Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            for (int i = 0; i < 16 && iterator.hasNext(); i++) {
                StackWalker.StackFrame frame = iterator.next();
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if (i < 2 || publicationMethod(type, method)) {
                    continue;
                }
                return Boolean.valueOf(("java.util.concurrent.FutureTask".equals(type) && "run".equals(method))
                        || ("java.util.concurrent.ForkJoinTask".equals(type) && "doExec".equals(method))
                        || ("java.util.concurrent.ForkJoinTask$InterruptibleTask".equals(type) && "exec".equals(method))
                        || (type.startsWith("java.util.concurrent.CompletableFuture$Async") && "run".equals(method)));
            }
            return Boolean.FALSE;
        }
    }

    private static boolean publicationMethod(String type, String method) {
        if ("java.util.concurrent.FutureTask".equals(type)) {
            return "set".equals(method) || "setException".equals(method);
        }
        if ("java.util.concurrent.ForkJoinTask".equals(type)
                || "java.util.concurrent.ForkJoinTask$RunnableExecuteAction".equals(type)) {
            return "setDone".equals(method)
                    || "trySetThrown".equals(method)
                    || "trySetException".equals(method)
                    || "setExceptionalCompletion".equals(method);
        }
        return "java.util.concurrent.CompletableFuture".equals(type)
                && ("completeValue".equals(method)
                        || "completeNull".equals(method)
                        || "completeThrowable".equals(method));
    }

    /** Tells {@code handle} the failure, if any, then closes it; never throws. */
    @SuppressWarnings("unchecked")
    static void exitHandle(Object handle, Throwable failure) {
        if (handle == null) {
            return;
        }
        try {
            if (failure != null && handle instanceof Consumer) {
                ((Consumer<Throwable>) handle).accept(failure);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        try {
            if (handle instanceof AutoCloseable) {
                ((AutoCloseable) handle).close();
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Substituted for {@code task.run()} in {@code ThreadPoolExecutor.runWorker}. */
    public static void runTask(Runnable task) {
        Object handle = enter(task, APPLY_RUN_WORKER);
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
            if (handle instanceof Active && !(task instanceof Future)) {
                Active active = (Active) handle;
                if (!active.completed && !active.nestedPublication) {
                    active.completed = true;
                    try {
                        ((Runnable) active.handle).run();
                    } catch (Throwable ex) {
                        AgentBridge.error(ex);
                    }
                }
            }
            exit(handle, thrown != null ? thrown : futureFailure(task));
        }
    }

    /** {@code ForkJoinTask.doExec} exit: the task's exceptional completion, if any. */
    public static void exitForkJoin(Object handle, Object task) {
        if (handle == null) {
            return;
        }
        Throwable failure = null;
        try {
            if (task instanceof ForkJoinTask && ((ForkJoinTask<?>) task).isCompletedAbnormally()) {
                failure = ((ForkJoinTask<?>) task).getException();
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        exit(handle, failure);
    }

    /** {@code CompletableFuture$AsyncSupply/AsyncRun.run} exit: the stage {@code dep} it completed, read on entry. */
    public static void exitAsync(Object handle, Object dependent) {
        if (handle == null) {
            return;
        }
        Throwable failure = null;
        try {
            if (dependent instanceof CompletableFuture
                    && ((CompletableFuture<?>) dependent).isCompletedExceptionally()) {
                try {
                    ((CompletableFuture<?>) dependent).join();
                } catch (CompletionException ex) {
                    failure = ex.getCause() != null ? ex.getCause() : ex;
                } catch (CancellationException ex) {
                    failure = ex;
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        exit(handle, failure);
    }

    /** A JDK {@code FutureTask} that completed exceptionally: its cause. Never blocks: only done tasks are read. */
    static Throwable futureFailure(Runnable task) {
        if (!(task instanceof FutureTask)) {
            return null;
        }
        try {
            FutureTask<?> future = (FutureTask<?>) task;
            if (future.isDone() && !future.isCancelled()) {
                future.get();
            }
        } catch (ExecutionException ex) {
            return ex.getCause();
        } catch (Throwable ignored) {
            // interrupted or cancelled meanwhile: no outcome
        }
        return null;
    }

    static boolean isAsyncTask(Object task) {
        String name = task.getClass().getName();
        return name.equals("java.util.concurrent.CompletableFuture$AsyncSupply")
                || name.equals("java.util.concurrent.CompletableFuture$AsyncRun");
    }

    /** A flat array of {@code String}, {@code Long}, {@code Integer}, {@code Boolean}, or {@code null} only. */
    static boolean flatJdkValues(Object payload) {
        if (payload == null || payload.getClass() != Object[].class) {
            return false;
        }
        Object[] values = (Object[]) payload;
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value != null) {
                Class<?> type = value.getClass();
                if (type != String.class && type != Long.class && type != Integer.class && type != Boolean.class) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- self-test and status ------------------------------------------------------------------------------------

    /** Starts the agent's self-test on the calling thread: its submissions are keyed with the self-test marker. */
    public static void beginSelfTest() {
        for (int i = 0; i < SELF_TEST_KEYED.length; i++) {
            SELF_TEST_KEYED[i].reset();
        }
        for (int i = 0; i < SELF_TEST_APPLIED.length; i++) {
            SELF_TEST_APPLIED[i].reset();
        }
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test: how many marker tasks each key and apply point saw. */
    public static Map<String, Object> endSelfTest() {
        selfTestThread = null;
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("keyed", counts(KEY_HOOKS, SELF_TEST_KEYED));
        map.put("applied", counts(APPLY_HOOKS, SELF_TEST_APPLIED));
        return map;
    }

    /** Disables keying and applying under {@code generation}, after its self-test failed. */
    public static void disable(long generation, String reason) {
        disabledReason = reason;
        disabledGeneration = generation;
        AgentBridge.message("executor propagation disabled: " + reason);
    }

    /** Whether {@code CompletableFuture$AsyncSupply/AsyncRun} are applied by their own hook rather than by the pool's. */
    public static void asyncApplies(boolean applies) {
        asyncApplies = applies;
    }

    /** Re-enables propagation, after a later self-test passed. */
    public static void enable() {
        disabledGeneration = -1L;
        disabledReason = null;
    }

    static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("keyed", counts(KEY_HOOKS, KEYED));
        map.put("applied", counts(APPLY_HOOKS, APPLIED));
        map.put("pending", Long.valueOf(TaskSnapshots.TASKS.size()));
        map.put("neverApplied", Long.valueOf(TaskSnapshots.TASKS.neverApplied()));
        map.put("ambiguous", Long.valueOf(AMBIGUOUS.sum()));
        map.put("stale", Long.valueOf(STALE.sum()));
        map.put("refused", Long.valueOf(REFUSED.sum()));
        map.put("virtualSkipped", Long.valueOf(VIRTUAL_SKIPPED.sum()));
        map.put("periodicSkipped", Long.valueOf(PERIODIC_SKIPPED.sum()));
        map.put("skippedTasks", Long.valueOf(SKIPPED_TASKS.sum()));
        map.put("skippedThreads", Long.valueOf(SKIPPED_THREADS.sum()));
        map.put("failures", Long.valueOf(FAILURES.sum()));
        map.put("disabledReason", disabledReason);
        map.put("asyncApplies", Boolean.valueOf(asyncApplies));
        return map;
    }

    static void reset() {
        TaskSnapshots.TASKS.reset();
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
        VIRTUAL_SKIPPED.reset();
        PERIODIC_SKIPPED.reset();
        SKIPPED_TASKS.reset();
        SKIPPED_THREADS.reset();
        FAILURES.reset();
        ACTIVE.remove();
        selfTestThread = null;
        asyncApplies = false;
        enable();
    }

    static Map<String, Object> counts(String[] names, LongAdder[] adders) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], Long.valueOf(adders[i].sum()));
        }
        return map;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }
}
