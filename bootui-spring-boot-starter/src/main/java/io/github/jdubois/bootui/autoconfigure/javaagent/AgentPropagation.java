package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Whether the BootUI agent propagates executor work for this application ({@code docs/PLAN-v2.md} M5-2), as the
 * request profile's {@code PROPAGATED} tier and the {@code work-after-response} observation ask it.
 */
public final class AgentPropagation {

    private AgentPropagation() {}

    /**
     * Why executor work is not propagated, or {@code null} when it is; without a Java Agent service, the generic
     * requirement. Never throws.
     */
    public static String unavailableReason(ObjectProvider<JavaAgentService> javaAgent) {
        try {
            JavaAgentService agent = javaAgent == null ? null : javaAgent.getIfUnique();
            return agent == null ? ProfileCapabilities.PROPAGATION_REASON : agent.propagationUnavailableReason();
        } catch (RuntimeException ex) {
            return ProfileCapabilities.PROPAGATION_REASON;
        }
    }
}
