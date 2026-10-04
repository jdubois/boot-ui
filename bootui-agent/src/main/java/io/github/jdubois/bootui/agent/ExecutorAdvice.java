package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import net.bytebuddy.asm.Advice;

/**
 * The executor sensor's advice (PLAN-v2 D32), inlined into JDK executor classes. Each advice is one static call into the
 * bootstrap bridge, so a JDK method grows by a few bytes, and suppresses every throwable, so the application never sees
 * the sensor fail. Key points read the task the executor actually received, after any other agent replaced it.
 */
final class ExecutorAdvice {

    private ExecutorAdvice() {}

    /** {@code ThreadPoolExecutor.addWorker(firstTask, core)}: keyed before the worker starts, released if it did not. */
    static final class AddWorker {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static int enter(@Advice.Argument(0) Runnable firstTask) {
            return TaskPropagation.workerOffered(firstTask);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter int keyed, @Advice.Argument(0) Runnable firstTask, @Advice.Return boolean started) {
            TaskPropagation.workerAdded(keyed, firstTask, started);
        }
    }

    /** {@code ThreadPoolExecutor.remove(task)}: a removed task never runs. */
    static final class Remove {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.Argument(0) Runnable task, @Advice.Return boolean removed) {
            if (removed) {
                TaskPropagation.release(task);
            }
        }
    }

    /** {@code ScheduledThreadPoolExecutor.delayedExecute(task)}: one-shot tasks only; released if rejected. */
    static final class DelayedExecute {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) Object task) {
            TaskPropagation.scheduled(task);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Argument(0) Object task, @Advice.This Object executor, @Advice.Thrown Throwable thrown) {
            TaskPropagation.scheduledDone(task, executor, thrown);
        }
    }

    /** {@code ForkJoinPool.externalSubmit(task)}: admission only, never {@code invoke}'s subsequent join. */
    static final class ForkJoinRoot {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter(@Advice.This Object pool, @Advice.Argument(0) Object task) {
            return TaskPropagation.forkJoinRoot(pool, task);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter boolean keyed, @Advice.Argument(0) Object task, @Advice.Thrown Throwable thrown) {
            TaskPropagation.forkJoinDone(keyed, task, thrown);
        }
    }

    /** Newer JDKs' {@code ForkJoinPool.poolSubmit(signal, task)} admission boundary. */
    static final class ForkJoinPoolSubmit {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter(@Advice.This Object pool, @Advice.Argument(1) Object task) {
            return TaskPropagation.forkJoinRoot(pool, task);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter boolean keyed, @Advice.Argument(1) Object task, @Advice.Thrown Throwable thrown) {
            TaskPropagation.forkJoinDone(keyed, task, thrown);
        }
    }

    /** Constructors of {@code ForkJoinTask$Adapted*} and {@code ForkJoinTask$RunnableExecuteAction}. */
    static final class Adapter {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object adapter, @Advice.Argument(value = 0, optional = true) Object wrapped) {
            TaskPropagation.adapterCreated(adapter, wrapped);
        }
    }

    /** {@code ForkJoinTask.fork()}: keyed only from a thread that is not a pool worker. */
    static final class Fork {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object task) {
            TaskPropagation.forked(task);
        }
    }

    /** JDK 25+ {@code DelayScheduler$ScheduledForkJoinTask(delay, period, fixedDelay, runnable, callable, pool)}. */
    static final class Delayed {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object task, @Advice.Argument(1) long period) {
            TaskPropagation.delayedCreated(task, period);
        }
    }

    /** {@code CompletableFuture$ThreadPerTaskExecutor.execute(task)} (JDK 17 to 25, below parallelism 2). */
    static final class ThreadPerTask {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter(@Advice.Argument(0) Object task) {
            return TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_PER_TASK);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter boolean keyed, @Advice.Argument(0) Object task, @Advice.Thrown Throwable thrown) {
            if (keyed && thrown != null) {
                TaskPropagation.release(task);
            }
        }
    }

    /** {@code ForkJoinTask.doExec()}: reopened around the task, with its exceptional completion. */
    static final class DoExec {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object task) {
            return TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object handle, @Advice.This Object task) {
            TaskPropagation.exitForkJoin(handle, task);
        }
    }

    /** {@code CompletableFuture$AsyncSupply.run()}: {@code dep} is read on entry, as {@code run} clears it. */
    static final class AsyncSupply {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(
                @Advice.This Object task,
                @Advice.FieldValue("dep") Object dependent,
                @Advice.Local("dependent") Object local) {
            local = dependent;
            return TaskPropagation.enter(task, TaskPropagation.APPLY_ASYNC_SUPPLY, dependent);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object handle, @Advice.Local("dependent") Object local) {
            TaskPropagation.exitAsync(handle, local);
        }
    }

    /** {@code CompletableFuture$AsyncRun.run()}: {@code dep} is read on entry, as {@code run} clears it. */
    static final class AsyncRun {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(
                @Advice.This Object task,
                @Advice.FieldValue("dep") Object dependent,
                @Advice.Local("dependent") Object local) {
            local = dependent;
            return TaskPropagation.enter(task, TaskPropagation.APPLY_ASYNC_RUN, dependent);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object handle, @Advice.Local("dependent") Object local) {
            TaskPropagation.exitAsync(handle, local);
        }
    }

    static final class BodyCompleted {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object target) {
            TaskPropagation.bodyCompleted(target);
        }
    }
}
