package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadActivity;
import net.bytebuddy.asm.Advice;

/**
 * The {@code thread-activity} sensor's delegating advice (PLAN-v2 §5.16, M5-5e): one static call into the bridge at
 * entry and, where needed, one at exit of each hooked JDK method, each suppressing its own exceptions. It advises the
 * entry and exit of {@code start}, constructors' exits, and {@code shutdown}'s entry only, never a thread's run path or
 * its scoped values, which M5-2's {@code threads} sensor substitutes. Each hook passes its own index, so the self-test
 * counts each apart.
 */
final class ThreadActivityAdvice {

    private ThreadActivityAdvice() {}

    /** {@code Thread.start()} and {@code start(ThreadContainer)}. */
    static final class Start {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter(@Advice.This Object thread) {
            return ThreadActivity.threadStarting(thread, SideEffects.HOOK_THREAD_START);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.This Object thread, @Advice.Thrown Throwable thrown) {
            ThreadActivity.threadStarted(token, thread, SideEffects.HOOK_THREAD_START, thrown);
        }
    }

    /** {@code VirtualThread.start(ThreadContainer)}, which {@code VirtualThread.start()} delegates to. */
    static final class VirtualStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter(@Advice.This Object thread) {
            return ThreadActivity.threadStarting(thread, SideEffects.HOOK_VIRTUAL_THREAD_START);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.This Object thread, @Advice.Thrown Throwable thrown) {
            ThreadActivity.threadStarted(token, thread, SideEffects.HOOK_VIRTUAL_THREAD_START, thrown);
        }
    }

    /** {@code ThreadPoolExecutor.addWorker}: the threads started meanwhile are the pool's workers. */
    static final class AddWorker {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter() {
            return ThreadActivity.poolStarting(SideEffects.HOOK_ADD_WORKER);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter boolean marked) {
            ThreadActivity.poolStarted(marked);
        }
    }

    /** {@code ThreadPerTaskExecutor.start(Thread)}: the thread it starts runs one of its tasks. */
    static final class PerTaskStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter() {
            return ThreadActivity.poolStarting(SideEffects.HOOK_PER_TASK_START);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter boolean marked) {
            ThreadActivity.poolStarted(marked);
        }
    }

    /**
     * The exit of {@code ThreadPoolExecutor}'s canonical constructor, which every other constructor of it and of {@code
     * ScheduledThreadPoolExecutor}, and every {@code Executors} factory returning one, reaches. Never {@code
     * onThrowable}, which a constructor cannot take: one that throws records nothing.
     */
    static final class ThreadPoolCreated {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object executor) {
            ThreadActivity.executorCreated(executor, SideEffects.HOOK_TPE_CREATED);
        }
    }

    /** The exit of {@code ForkJoinPool}'s canonical public constructor. */
    static final class ForkJoinCreated {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object executor) {
            ThreadActivity.executorCreated(executor, SideEffects.HOOK_FJP_CREATED);
        }
    }

    /** The exit of {@code ThreadPerTaskExecutor}'s constructor. */
    static final class PerTaskCreated {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object executor) {
            ThreadActivity.executorCreated(executor, SideEffects.HOOK_PER_TASK_CREATED);
        }
    }

    /** {@code ThreadPoolExecutor.shutdown()}. */
    static final class ThreadPoolShutdown {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object executor) {
            ThreadActivity.executorShuttingDown(executor, SideEffects.HOOK_TPE_SHUTDOWN);
        }
    }

    /** {@code ThreadPoolExecutor.shutdownNow()}. */
    static final class ThreadPoolShutdownNow {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object executor) {
            ThreadActivity.executorShuttingDown(executor, SideEffects.HOOK_TPE_SHUTDOWN_NOW);
        }
    }

    /** {@code ForkJoinPool.shutdown()}, {@code shutdownNow()}, and {@code close()}. */
    static final class ForkJoinShutdown {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object executor) {
            ThreadActivity.executorShuttingDown(executor, SideEffects.HOOK_FJP_SHUTDOWN);
        }
    }

    /** {@code ThreadPerTaskExecutor.shutdown()}, {@code shutdownNow()}, and {@code close()}. */
    static final class PerTaskShutdown {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object executor) {
            ThreadActivity.executorShuttingDown(executor, SideEffects.HOOK_PER_TASK_SHUTDOWN);
        }
    }
}
