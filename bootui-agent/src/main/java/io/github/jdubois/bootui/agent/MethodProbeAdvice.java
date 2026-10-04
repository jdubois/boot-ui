package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.bytebuddy.asm.Advice;

/**
 * A method probe's advice (PLAN-v2 §5.14, M5-8), inlined around the one probed method: one static call at entry,
 * returning the invocation's start or 0, and one at every exit, normal or by a throwable, with that start and the
 * throwable. The probe's slot and id are bound as constants ({@link Slot}, {@link ProbeId}), so advice left behind by a
 * probe that ended records nothing. Both calls suppress their own exceptions; the advice never reads or changes an
 * argument or the return value.
 */
final class MethodProbeAdvice {

    private MethodProbeAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static long enter(@Slot int slot, @ProbeId long id) {
        return MethodProbes.enter(slot, id);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Slot int slot, @ProbeId long id, @Advice.Enter long started, @Advice.Thrown Throwable thrown) {
        MethodProbes.exit(slot, id, started, thrown);
    }

    /** The probe's slot in {@link MethodProbes}, bound as a constant. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Slot {}

    /** The probe's id, bound as a constant. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface ProbeId {}
}
