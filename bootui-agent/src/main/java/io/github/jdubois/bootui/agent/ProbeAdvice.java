package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import net.bytebuddy.asm.Advice;

/** The diagnostic probe: one call into the bootstrap bridge at method entry. Used only through {@link AgentTestHook}. */
final class ProbeAdvice {

    private ProbeAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter() {
        AgentBridge.probe();
    }
}
