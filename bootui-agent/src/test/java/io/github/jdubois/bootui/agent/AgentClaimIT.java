package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A claim with the diagnostic probe, in forked JVMs against the test variant of the jar (PLAN-v2 M5-1). */
class AgentClaimIT {

    @Test
    void theProbeInstallsCountsAndIsRemovedOnRelease() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.TEST_AGENT), "-Dbootui.agent.it.probe=bootuiagentit.probed"),
                "probe");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("CLAIM")).isEqualTo("armed");
        assertThat(output.value("GREETING")).isEqualTo("hello agent");
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("RELEASE")).isEqualTo("released");
        assertThat(output.value("HITS_AFTER_RELEASE")).isEqualTo("0");
        assertThat(output.value("INSTALLER"))
                .as(output.toString())
                .contains("state=released")
                .contains("failed=0");
    }

    @Test
    void aFailingAdviceNeverReachesTheApplication() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.TEST_AGENT),
                        "-Dbootui.agent.it.probe=bootuiagentit.probed",
                        "-Dbootui.agent.it.throwing=true"),
                "probe");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("GREETING")).isEqualTo("hello agent");
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("INSTALLER")).contains("advice=ThrowingProbeAdvice");
    }

    @Test
    void aClaimWithThePublishedJarInstallsNothing() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.AGENT),
                        "-Dbootui.agent.it.probe=bootuiagentit.probed",
                        "-Xlog:class+load=info"),
                "claim-only");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("CLAIM")).contains("status=armed");
        assertThat(output.value("INSTALLER")).isEqualTo("{state=none}");
        assertThat(output.text()).doesNotContain("shaded.bytebuddy");
    }
}
