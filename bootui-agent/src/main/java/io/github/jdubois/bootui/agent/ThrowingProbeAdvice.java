package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import net.bytebuddy.asm.Advice;

/** A probe whose advice fails after counting: proves {@code suppress = Throwable.class} keeps it from the application. */
final class ThrowingProbeAdvice {

    private ThrowingProbeAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter() {
        AgentBridge.probe();
        if (AgentBridge.recording()) {
            throw new IllegalStateException("BootUI agent test: failing advice");
        }
    }
}
