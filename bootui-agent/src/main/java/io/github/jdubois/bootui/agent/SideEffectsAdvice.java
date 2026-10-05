package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.Blocking;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * The side-effect sensors' delegating advice (PLAN-v2 §5.16, M5-5): one static call into the bridge at entry and one
 * at exit of each hooked JDK method, both suppressing their own exceptions, so the advice never changes what the method
 * does or throws.
 */
final class SideEffectsAdvice {

    private SideEffectsAdvice() {}

    /**
     * {@code ProcessBuilder.start(Redirect[])}, which {@code start()}, {@code startPipeline}, and {@code Runtime.exec}
     * all reach.
     */
    static final class ProcessStart {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return SideEffects.processStarting();
        }

        /**
         * Reads the {@code command} field, never calls {@code command()}, so a Mockito spy of {@code ProcessBuilder}
         * records no extra interaction.
         */
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(
                @Advice.Enter long token,
                @Advice.FieldValue("command") List<String> command,
                @Advice.Return Process process,
                @Advice.Thrown Throwable thrown) {
            SideEffects.processStarted(token, command, process, thrown);
        }
    }

    /**
     * {@code LockSupport.park}, {@code parkNanos}, and {@code parkUntil}, with and without a blocker: the bridge returns
     * at entry off event loops. The exit also runs when the park throws, as BlockHound's callback does from inside it,
     * so the thread's hook is always closed.
     */
    static final class Park {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter() {
            return Blocking.parking();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter long token, @Advice.Thrown Throwable thrown) {
            Blocking.parked(token, thrown);
        }
    }
}
