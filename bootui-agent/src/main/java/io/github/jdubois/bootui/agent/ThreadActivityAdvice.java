package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.ThreadActivity;
import net.bytebuddy.asm.Advice;

/**
 * The {@code thread-activity} sensor's delegating advice (PLAN-v2 §5.16, M5-5e): one static call into the bridge at
 * entry and, where needed, one at exit of each hooked JDK method, each suppressing its own exceptions. It advises the
 * entry and exit of {@code start}, constructors' exits, and {@code shutdown}'s entry only, never a thread's run path or
 * its scoped values, which M5-2's {@code threads} sensor substitutes.
 */
final class ThreadActivityAdvice {

    private ThreadActivityAdvice() {}

    /** {@code Thread.start()} and {@code start(ThreadContainer)}, and {@code VirtualThread.start(ThreadContainer)}. */
    static final class Start {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter(@Advice.This Object thread) {
            return ThreadActivity.threadStarting(thread);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.This Object thread, @Advice.Thrown Throwable thrown) {
            ThreadActivity.threadStarted(token, thread, thrown);
        }
    }

    /**
     * Where a pool starts its own threads, as {@code ThreadPoolExecutor.addWorker}: those threads are the executor's,
     * which is recorded, never threads of their own.
     */
    static final class PoolStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter() {
            return ThreadActivity.poolStarting();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter boolean entered) {
            ThreadActivity.poolStarted(entered);
        }
    }

    /**
     * The exit of an executor's canonical constructor, which every other constructor and {@code Executors} factory of
     * its class reaches; never {@code onThrowable}, which a constructor cannot take: a constructor that throws records
     * nothing.
     */
    static final class Created {

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object executor) {
            ThreadActivity.executorCreated(executor);
        }
    }

    /** An executor's {@code shutdown}, {@code shutdownNow}, or {@code close}. */
    static final class Shutdown {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object executor) {
            ThreadActivity.executorShuttingDown(executor);
        }
    }
}
