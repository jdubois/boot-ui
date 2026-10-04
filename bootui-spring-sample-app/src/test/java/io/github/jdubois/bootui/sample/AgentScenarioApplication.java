package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationContextInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * The sample with the correlation scenario's routes, started by {@link SpringAgentScenarioIT} in a JVM of its own with
 * the BootUI agent attached ({@code docs/PLAN-v2.md} M5-2).
 */
public final class AgentScenarioApplication {

    private AgentScenarioApplication() {}

    public static void main(String[] args) {
        SpringApplication application =
                new SpringApplication(BootUiSampleApplication.class, CorrelationScenarioRoutes.class);
        application.addListeners(new AwaitInventorySensor());
        application.run(args);
    }

    /**
     * Waits, bounded, until the agent's inventory sensor installed for the claim BootUI's environment post-processor
     * made: the sensor installs off the claiming thread, and a seed class loading before it is honestly reported as
     * possibly run before instrumentation, which the scenario's assertions do not expect.
     */
    static final class AwaitInventorySensor implements ApplicationListener<ApplicationContextInitializedEvent> {

        @Override
        public void onApplicationEvent(ApplicationContextInitializedEvent event) {
            AgentBridgeAccess access = AgentBridgeAccess.locate();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (access.compatible() && !installed(access.status()) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (access.compatible() && !installed(access.status())) {
                System.err.println(
                        "AgentScenarioApplication: the inventory sensor did not install in time: " + access.status());
            }
        }

        private static boolean installed(Map<String, Object> status) {
            if (status.get("agent") instanceof Map<?, ?> agent
                    && agent.get("sensors") instanceof Collection<?> sensors) {
                for (Object sensor : sensors) {
                    if (sensor instanceof Map<?, ?> row && "inventory".equals(row.get("id"))) {
                        return "installed".equals(row.get("state"));
                    }
                }
            }
            return false;
        }
    }
}
