package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Claims the BootUI Java agent at static init in dev and test launch modes ({@code docs/PLAN-v2.md} D34), as early as
 * Quarkus allows, with values captured at build time: it reads no runtime configuration. A live reload runs static init
 * again in the same slot, which replaces the previous start's claim. Without the agent's bridge on the bootstrap class
 * path it claims nothing.
 */
@Recorder
public class BootUiAgentRecorder {

    /** Claims the agent for this start of {@code application}, with the default sensors. */
    public RuntimeValue<QuarkusAgentClaim> claim(String application, String mode, List<String> packages) {
        AgentSensorSettings defaults = AgentSensorSettings.defaults();
        return claim(
                application,
                mode,
                packages,
                defaults.sensors(),
                defaults.skipTasks(),
                defaults.skipThreads(),
                defaults.maxHandoff().toMillis(),
                defaults.ringCapacity());
    }

    /**
     * Claims the agent for this start of {@code application}, asking for {@code sensors} ({@code bootui.agent.sensors})
     * with the executors sensor's options ({@code bootui.agent.executors.*}) and the transport ring's capacity
     * ({@code bootui.agent.ring-capacity}).
     */
    public RuntimeValue<QuarkusAgentClaim> claim(
            String application,
            String mode,
            List<String> packages,
            List<String> sensors,
            List<String> skipTasks,
            List<String> skipThreads,
            long maxHandoffMillis,
            int ringCapacity) {
        AgentBridgeAccess access = AgentBridgeAccess.locate();
        if (!access.present()) {
            return new RuntimeValue<>(QuarkusAgentClaim.none());
        }
        String owner = application + "@" + UUID.randomUUID().toString().substring(0, 8);
        AgentSensorSettings settings = new AgentSensorSettings(
                sensors, skipTasks, skipThreads, Duration.ofMillis(maxHandoffMillis), ringCapacity);
        return new RuntimeValue<>(
                new QuarkusAgentClaim(AgentClaim.claim(access, application, owner, mode, packages, settings)));
    }

    /**
     * Releases the agent because this application's agent support is off ({@code bootui.agent.enabled=false}): removes
     * its transformers unless another application's armed claim holds it.
     */
    public RuntimeValue<QuarkusAgentClaim> release(String application, String mode) {
        AgentBridgeAccess access = AgentBridgeAccess.locate();
        if (access.present()) {
            AgentClaim.release(access, application, mode);
        }
        return new RuntimeValue<>(QuarkusAgentClaim.none());
    }
}
