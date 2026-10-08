package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Claims the BootUI Java agent at static init in dev and test launch modes ({@code docs/PLAN-v2.md} D34), as early as
 * Quarkus allows, with values captured at build time: it reads no runtime configuration. A live reload runs static init
 * again in the same slot, which replaces the previous start's claim. Without the agent's bridge on the bootstrap class
 * path it claims nothing.
 */
@Recorder
public class BootUiAgentRecorder {

    private static final Logger LOG = Logger.getLogger(BootUiAgentRecorder.class.getName());

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
        return claim(
                application,
                mode,
                packages,
                sensors,
                skipTasks,
                skipThreads,
                maxHandoffMillis,
                ringCapacity,
                List.of());
    }

    /**
     * Claims the agent for this start of {@code application}, as {@link #claim(String, String, List, List, List, List,
     * long, int)} does, with the application archive's bean classes for the {@code code-paths} sensor, read at build
     * time ({@code docs/PLAN-v2.md} M5-4a).
     */
    public RuntimeValue<QuarkusAgentClaim> claim(
            String application,
            String mode,
            List<String> packages,
            List<String> sensors,
            List<String> skipTasks,
            List<String> skipThreads,
            long maxHandoffMillis,
            int ringCapacity,
            List<String> beanClasses) {
        AgentBridgeAccess access = AgentBridgeAccess.locate();
        if (!access.present()) {
            return new RuntimeValue<>(QuarkusAgentClaim.none());
        }
        String owner = application + "@" + UUID.randomUUID().toString().substring(0, 8);
        AgentSensorSettings settings;
        try {
            settings = new AgentSensorSettings(
                    sensors, skipTasks, skipThreads, Duration.ofMillis(maxHandoffMillis), ringCapacity);
        } catch (IllegalArgumentException ex) {
            // Only with the agent attached, as on Spring: an id no version of the agent lists fails the start.
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        String notAvailable = settings.notAvailableWarning();
        if (notAvailable != null) {
            LOG.warning(notAvailable);
        }
        return new RuntimeValue<>(new QuarkusAgentClaim(
                AgentClaim.claim(access, application, owner, mode, packages, settings, beanClasses)));
    }

    /**
     * Sets {@code bootui.agent.security-sinks.request-values}, read at build time: the adapters push no request value
     * unless it is on ({@code docs/PLAN-v2.md} M5-6b, D37).
     */
    public void requestValues(boolean on) {
        AgentRequestValues.configure(on);
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
        return new RuntimeValue<>(QuarkusAgentClaim.disabled());
    }
}
