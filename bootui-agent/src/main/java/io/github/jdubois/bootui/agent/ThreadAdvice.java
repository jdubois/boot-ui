package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.ThreadPropagation;
import net.bytebuddy.asm.Advice;

/**
 * The threads sensor's advice (PLAN-v2 M5-2c). {@code Thread.start} and {@code VirtualThread.start(ThreadContainer)}
 * key the thread at entry, before {@code start0} can run it, and release it when the start fails. Applying is done by
 * member substitution of {@code task.run()} in {@code Thread.run} and {@code Thread.runWith}, never by advice there, so
 * the scoped-value bindings {@code runWith} keeps in its frame stay where the JVM looks for them.
 */
final class ThreadAdvice {

    private ThreadAdvice() {}

    /** {@code Thread.start()} and {@code Thread.start(ThreadContainer)} of a platform thread. */
    static final class Start {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter(@Advice.This Object thread) {
            return ThreadPropagation.starting(thread, ThreadPropagation.KEY_THREAD_START);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter boolean keyed, @Advice.This Object thread, @Advice.Thrown Throwable thrown) {
            ThreadPropagation.started(keyed, thread, thrown);
        }
    }

    /** {@code VirtualThread.start(ThreadContainer)}, which {@code VirtualThread.start()} delegates to. */
    static final class VirtualStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static boolean enter(@Advice.This Object thread) {
            return ThreadPropagation.starting(thread, ThreadPropagation.KEY_VIRTUAL_START);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter boolean keyed, @Advice.This Object thread, @Advice.Thrown Throwable thrown) {
            ThreadPropagation.started(keyed, thread, thrown);
        }
    }

    /** {@code ThreadPoolExecutor.addWorker}: the threads started meanwhile are the pool's workers, never keyed. */
    static final class AddingWorker {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() {
            ThreadPropagation.addingWorker(true);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit() {
            ThreadPropagation.addingWorker(false);
        }
    }

    /** {@code run()} of a {@code Thread} subclass in the claimed packages that overrides it. */
    static final class SubclassRun {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object thread) {
            return ThreadPropagation.enterSubclass(thread);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object handle, @Advice.Thrown Throwable thrown) {
            ThreadPropagation.exit(handle, thrown);
        }
    }
}
