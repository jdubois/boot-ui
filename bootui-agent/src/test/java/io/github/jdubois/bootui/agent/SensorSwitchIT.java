package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runtime sensor switches in a forked JVM against the published jar (PLAN-v2 M5-14): {@code environment} and
 * {@code threads} switched on and off in one run, without a new claim, and a switch kept by the same application's next
 * claim.
 */
class SensorSwitchIT {

    private static final List<String> REQUIRED = List.of(
            "before the switch, a property read records nothing",
            "switching environment on installs and self-tests it in this run, which records a read",
            "switching threads on installs and self-tests it in this run",
            "switching environment off stops its recording at once and removes its hooks, processes recording on",
            "switching threads off restores java.lang.Thread",
            "the same application's next claim keeps the switch, and the previous run's token switches nothing",
            "a configured sensor switched off records nothing and releases its hooks, and switched back on records"
                    + " again");

    @Test
    void sensorsSwitchOnAndOffInTheRunningJvm() throws Exception {
        ChildJvm.Output output = ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "sensor-switch-behaviors");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        String stdout = output.text();
        for (String behavior : REQUIRED) {
            assertThat(stdout).as(stdout).contains("  PASS " + behavior);
        }
        assertThat(stdout).as(stdout).doesNotContain("  FAIL ");
    }
}
