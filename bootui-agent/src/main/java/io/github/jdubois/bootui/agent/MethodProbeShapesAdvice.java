package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * A shapes probe's advice (PLAN-v2 §5.14, M5-8, D44): {@link MethodProbeAdvice}'s calls, plus the arguments at entry and
 * the returned value at a normal exit, passed to the bridge only for an invocation {@link MethodProbes#enter} recorded.
 * Byte Buddy inlines the advice and builds the argument array, and boxes a primitive return value, where the advice
 * reads them, inside those branches: an invocation the probe does not record, after the probe ended or past its bound,
 * allocates nothing more than {@link MethodProbeAdvice}'s. The bridge reads only the values' classes and allowlisted JDK
 * accessors ({@code ProbeShapes}); the advice never changes an argument or the return value.
 */
final class MethodProbeShapesAdvice {

    private MethodProbeShapesAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static long enter(
            @MethodProbeAdvice.Slot int slot,
            @MethodProbeAdvice.ProbeId long id,
            @Advice.AllArguments Object[] arguments) {
        long started = MethodProbes.enter(slot, id);
        if (started != 0L) {
            MethodProbes.arguments(slot, id, started, arguments);
        }
        return started;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
            @MethodProbeAdvice.Slot int slot,
            @MethodProbeAdvice.ProbeId long id,
            @Advice.Enter long started,
            @Advice.Thrown Throwable thrown,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned) {
        if (started != 0L) {
            MethodProbes.exit(slot, id, started, thrown, returned);
        }
    }
}
