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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
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
 * body-completion marker before normal JDK result publication, or at an explicitly early-published body's return.
 * An optional {@code IntConsumer} receives {@code 1} before an early manual result publication and {@code 2} for a
 * nested publication inside a plain runnable. Plain runnables are marked at their own return, never at a nested
 * future's publication.
 */
public final class TaskPropagation {

    public static final String SENSOR = "executors";

    /** Key points: where an executor receives a task. {@code ThreadPoolExecutor.addWorker}'s first task. */
    public static final int KEY_THREAD_POOL = 0;

    public static final int KEY_SCHEDULED = 1;
    public static final int KEY_FORK_JOIN_ROOT = 2;
    public static final int KEY_FORK_JOIN_ADAPTER = 3;
    public static final int KEY_FORK = 4;
    public static final int KEY_DELAYED = 5;
    public static final int KEY_THREAD_PER_TASK = 6;
    /** {@code ThreadPoolExecutor.execute}'s {@code workQueue.offer}: a separate path from {@code addWorker}. */
    public static final int KEY_THREAD_POOL_QUEUE = 7;

    /** Apply points: where an executor runs a task. */
    public static final int APPLY_RUN_WORKER = 0;

    public static final int APPLY_DO_EXEC = 1;
    public static final int APPLY_ASYNC_SUPPLY = 2;
    public static final int APPLY_ASYNC_RUN = 3;

    static final String[] KEY_HOOKS = {
        "ThreadPoolExecutor.addWorker",
        "ScheduledThreadPoolExecutor",
        "ForkJoinPool",
        "ForkJoinTask adapters",
        "ForkJoinTask.fork",
        "DelayScheduler",
        "CompletableFuture.ThreadPerTaskExecutor",
        "ThreadPoolExecutor.queue"
    };
    static final String[] APPLY_HOOKS = {
        "ThreadPoolExecutor.runWorker",
        "ForkJoinTask.doExec",
        "CompletableFuture.AsyncSupply",
        "CompletableFuture.AsyncRun"
    };

    /** The self-test's snapshot: recognized by identity, never reachable through the engine's capture. */
    static final Object[] SELF_TEST = new Object[] {"bootui-agent-self-test"};

    private static volatile Thread selfTestThread;
    /** A claim generation whose self-test failed: no keying and no applying under it. */
    private static volatile long disabledGeneration = -1L;

    private static volatile String disabledReason;
    /**
     * Whether {@code CompletableFuture$AsyncSupply}'s own advice applies it: set only once that class's hook passed the
     * self-test of the installed transformer, cleared before any reinstall or retest. Until then the pool's hook applies
     * it, so a missing or unverified hook never loses its snapshot.
     */
    private static volatile boolean asyncSupplyApplies;
    /** The same, for {@code CompletableFuture$AsyncRun}. */
    private static volatile boolean asyncRunApplies;

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
    static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final BodyCaller BODY_CALLER = new BodyCaller();

    private TaskPropagation() {}

    private static final class Active {
        // Only the worker's dynamic scope holds these references; exit removes it even when closing fails.
        final Object target;
        final Object handle;
        final Active previous;
        boolean completed;
        boolean ownPublished;

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
        int outcome = submit(task, hook, true);
        return outcome != NONE && outcome != OVERFLOWED;
    }

    static final int NONE = 0;
    static final int TOUCHED = 1;
    static final int KEYED_OWNED = 2;
    /** The registry was full: nothing is keyed; counted as overflow once the executor accepted the task. */
    static final int OVERFLOWED = 3;

    /**
     * Keys {@code task}; {@code count} says whether a keyed submission counts now, or only once the executor accepted it
     * ({@link #confirm}). Returns {@link #NONE}, {@link #TOUCHED} (an existing entry made ambiguous),
     * {@link #KEYED_OWNED}, or {@link #OVERFLOWED}.
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
                if (TaskSnapshots.TASKS.putSelfTest(task, generation, SELF_TEST) == TaskSnapshots.REFUSED) {
                    return NONE;
                }
                SELF_TEST_KEYED[hook].increment();
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
                return TaskSnapshots.TASKS.putUnowned(task) ? TOUCHED : NONE;
            }
            if (!flatJdkValues(payload)) {
                REFUSED.increment();
                return TaskSnapshots.TASKS.putUnowned(task) ? TOUCHED : NONE;
            }
            int put = TaskSnapshots.TASKS.put(task, generation, (Object[]) payload, Math.max(0L, CodePaths.stamp()));
            if (put == TaskSnapshots.REFUSED) {
                // The registry is full: nothing is keyed, nothing needs a release, and the task runs unowned.
                if (count) {
                    TaskSnapshots.TASKS.overflowed();
                }
                return OVERFLOWED;
            }
            if (count) {
                KEYED[hook].increment();
            }
            if (put == TaskSnapshots.AMBIGUOUS_PUT) {
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
        } else if (outcome != OVERFLOWED) {
            release(firstTask);
        }
    }

    static void confirm(int outcome, int hook) {
        if (outcome == KEYED_OWNED && sensorOn(AgentBridge.current())) {
            KEYED[hook].increment();
        } else if (outcome == OVERFLOWED && sensorOn(AgentBridge.current())) {
            TaskSnapshots.TASKS.overflowed();
        }
    }

    /** Substituted for {@code workQueue.offer(command)} in {@code ThreadPoolExecutor.execute}: keys what is queued. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean offer(BlockingQueue queue, Object task) {
        int outcome = submit(task, KEY_THREAD_POOL_QUEUE, false);
        boolean queued = false;
        try {
            queued = queue.offer(task);
            return queued;
        } finally {
            if (outcome != NONE) {
                if (queued) {
                    confirm(outcome, KEY_THREAD_POOL_QUEUE);
                } else if (outcome != OVERFLOWED) {
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
            if (sensorOn(AgentBridge.current())) {
                PERIODIC_SKIPPED.increment();
            }
        } else {
            submitted(task, KEY_SCHEDULED);
        }
    }

    /**
     * A root {@code ForkJoinTask} submitted to a {@code ForkJoinPool}. The pool's own adapters are keyed where they are
     * constructed, and virtual threads' continuations never.
     */
    public static boolean forkJoinRoot(Object pool, Object task) {
        if (!(task instanceof ForkJoinTask)) {
            return false;
        }
        Thread current = Thread.currentThread();
        if (current instanceof ForkJoinWorkerThread && ((ForkJoinWorkerThread) current).getPool() == pool) {
            // A worker's own subtask, not a root submission.
            return false;
        }
        String name = task.getClass().getName();
        if (name.startsWith("java.util.concurrent.ForkJoinTask$")
                || name.startsWith("java.util.concurrent.DelayScheduler$")) {
            return false;
        }
        return submitted(task, KEY_FORK_JOIN_ROOT);
    }

    /** Admission-only advice: a rejection releases its submission, independently of the task's completion state. */
    public static void forkJoinDone(boolean keyed, Object task, Throwable thrown) {
        if (keyed && thrown instanceof RejectedExecutionException) {
            release(task);
        }
    }

    /** Constructors of {@code ForkJoinTask$Adapted*} and {@code $RunnableExecuteAction}: skips virtual threads. */
    public static void adapterCreated(Object adapter, Object wrapped) {
        if (wrapped != null && wrapped.getClass().getName().startsWith("java.lang.VirtualThread")) {
            if (sensorOn(AgentBridge.current())) {
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
            if (sensorOn(AgentBridge.current())) {
                PERIODIC_SKIPPED.increment();
            }
            return;
        }
        submitted(task, KEY_DELAYED);
    }

    /** Earlier-generation tasks are never reopened, but stay retained to preserve cross-generation ambiguity. */
    static void claimed(long generation) {
        try {
            TaskSnapshots.TASKS.releaseEarlierClaims(generation);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
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
            int async = asyncHook(task);
            if (async >= 0 && hook != async) {
                if (async == APPLY_ASYNC_SUPPLY ? asyncSupplyApplies : asyncRunApplies) {
                    // CompletableFuture's own verified advice applies it, where the stage's outcome is readable.
                    return null;
                }
                if (TaskSnapshots.TASKS.peek(task) == SELF_TEST) {
                    // Left for the class's own hook to prove itself; released after the run if that hook is absent.
                    return new Deferred(task);
                }
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
            if (!sensorOn(claim)) {
                return null;
            }
            if (claim.generation != snapshot.generation) {
                STALE.increment();
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
                // The work's code-paths fragment records the node that submitted it (PLAN-v2 §5.14, design I7).
                CodePaths.handoff(snapshot.stamp);
                // The side-effect sensors' owner slot names the submitting request (PLAN-v2 M5-5 design B1).
                // A pool's own worker running it: the thread-locals sensor scans when the task ends (M5-5f).
                SideEffects.handoff(snapshot.payload, snapshot.generation, ThreadLocals.pooled(hook));
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
        exit(handle, failure, true);
    }

    private static void exit(Object handle, Throwable failure, boolean bodyFailure) {
        if (handle != null && failure != null) {
            FAILURES.increment();
        }
        if (handle instanceof Active) {
            Active active = (Active) handle;
            try {
                exitHandle(active.handle, failure, bodyFailure);
            } finally {
                if (active.previous == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(active.previous);
                }
            }
        } else {
            exitHandle(handle, failure, bodyFailure);
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
                if (!(active.target instanceof Future) && active.handle instanceof IntConsumer) {
                    ((IntConsumer) active.handle).accept(2);
                }
                return;
            }
            if (!jdkBodyReturned()) {
                if (!active.ownPublished && target instanceof Future && !((Future<?>) target).isDone()) {
                    active.ownPublished = true;
                    if (active.handle instanceof IntConsumer) {
                        ((IntConsumer) active.handle).accept(1);
                    }
                }
                return;
            }
            if (target instanceof ForkJoinTask && ((ForkJoinTask<?>) target).isDone() && !active.ownPublished) {
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

    private static boolean sensorOn(Claim claim) {
        return claim != null && claim.armed && claim.hasSensor(SENSOR) && claim.generation != disabledGeneration;
    }

    /** Tells {@code handle} the failure, if any, then closes it; never throws. */
    static void exitHandle(Object handle, Throwable failure) {
        exitHandle(handle, failure, true);
    }

    @SuppressWarnings("unchecked")
    private static void exitHandle(Object handle, Throwable failure, boolean bodyFailure) {
        if (handle == null) {
            return;
        }
        if (!(handle instanceof Deferred)) {
            // Matches the handoff where the handle was opened: the thread's previous submitter is back, as when an
            // executor ran the work on the submitting thread inside work it ran itself.
            CodePaths.handoffDone();
            SideEffects.handoffDone();
        }
        try {
            if (failure != null && handle instanceof BiConsumer) {
                ((BiConsumer<Throwable, Boolean>) handle).accept(failure, Boolean.valueOf(bodyFailure));
            } else if (failure != null && handle instanceof Consumer) {
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
            if (handle instanceof Active) {
                Active active = (Active) handle;
                if (!active.completed && (!(task instanceof Future) || active.ownPublished)) {
                    active.completed = true;
                    try {
                        ((Runnable) active.handle).run();
                    } catch (Throwable ex) {
                        AgentBridge.error(ex);
                    }
                }
            }
            exit(handle, thrown != null ? thrown : futureFailure(task), thrown == null || !(task instanceof Future));
        }
    }

    /** {@code ForkJoinTask.doExec} exit: the task's exceptional completion, if any. */
    public static void exitForkJoin(Object handle, Object task) {
        if (handle == null) {
            return;
        }
        Throwable failure = null;
        try {
            if (handle instanceof Active) {
                Active active = (Active) handle;
                if (active.target == task && active.ownPublished && !active.completed) {
                    active.completed = true;
                    ((Runnable) active.handle).run();
                }
            }
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

    /** {@link #APPLY_ASYNC_SUPPLY} or {@link #APPLY_ASYNC_RUN} for {@code CompletableFuture}'s async tasks, else -1. */
    static int asyncHook(Object task) {
        String name = task.getClass().getName();
        if (name.equals("java.util.concurrent.CompletableFuture$AsyncSupply")) {
            return APPLY_ASYNC_SUPPLY;
        }
        if (name.equals("java.util.concurrent.CompletableFuture$AsyncRun")) {
            return APPLY_ASYNC_RUN;
        }
        return -1;
    }

    /** A self-test async task a pool hook left to its own hook: releases its marker entry once the task ran. */
    static final class Deferred implements AutoCloseable {

        private final Object task;

        Deferred(Object task) {
            this.task = task;
        }

        @Override
        public void close() {
            release(task);
        }
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

    /**
     * Whether {@code CompletableFuture$AsyncSupply} ({@link #APPLY_ASYNC_SUPPLY}) or {@code AsyncRun}
     * ({@link #APPLY_ASYNC_RUN}) is applied by its own hook rather than by the pool's: true only once that class's own
     * hook passed its self-test.
     */
    public static void asyncApplies(int hook, boolean applies) {
        if (hook == APPLY_ASYNC_SUPPLY) {
            asyncSupplyApplies = applies;
        } else if (hook == APPLY_ASYNC_RUN) {
            asyncRunApplies = applies;
        }
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
        map.put("overflow", Long.valueOf(TaskSnapshots.TASKS.overflow()));
        map.put("virtualSkipped", Long.valueOf(VIRTUAL_SKIPPED.sum()));
        map.put("periodicSkipped", Long.valueOf(PERIODIC_SKIPPED.sum()));
        map.put("skippedTasks", Long.valueOf(SKIPPED_TASKS.sum()));
        map.put("skippedThreads", Long.valueOf(SKIPPED_THREADS.sum()));
        map.put("failures", Long.valueOf(FAILURES.sum()));
        map.put("disabledReason", disabledReason);
        map.put("asyncApplies", Boolean.valueOf(asyncSupplyApplies && asyncRunApplies));
        map.put("asyncSupplyApplies", Boolean.valueOf(asyncSupplyApplies));
        map.put("asyncRunApplies", Boolean.valueOf(asyncRunApplies));
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
        asyncSupplyApplies = false;
        asyncRunApplies = false;
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
