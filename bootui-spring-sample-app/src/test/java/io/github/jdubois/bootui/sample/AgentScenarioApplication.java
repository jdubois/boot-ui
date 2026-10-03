package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import org.springframework.boot.SpringApplication;

/**
 * The sample with the correlation scenario's routes, started by {@link SpringAgentScenarioIT} in a JVM of its own with
 * the BootUI agent attached ({@code docs/PLAN-v2.md} M5-2).
 */
public final class AgentScenarioApplication {

    private AgentScenarioApplication() {}

    public static void main(String[] args) {
        new SpringApplication(BootUiSampleApplication.class, CorrelationScenarioRoutes.class).run(args);
    }
}
