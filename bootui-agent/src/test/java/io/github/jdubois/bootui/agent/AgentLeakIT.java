package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The agent-rooted leak walk (PLAN-v2 M5-0 second pass, M5-1 acceptance): ten simulated DevTools runs, each in its own
 * child-first class loader, claim and disarm with the probe installed; afterwards the agent must strongly reach no run's
 * class loader. Each mutation must be caught, so the test can fail.
 */
class AgentLeakIT {

    private static final int RUNS = 10;
    private static final String RUN_CLASS = "bootuiagentit/run/RunApp";

    @Test
    void theAgentReachesNoRunAfterTenRuns() throws Exception {
        Map<Integer, String> reached = reached("clean", List.of());

        assertThat(reached).as("runs the agent strongly reaches: %s", reached).isEmpty();
    }

    @Test
    void aTransformerKeepingTheFirstRunsContextClassLoaderIsCaught() throws Exception {
        Map<Integer, String> reached = reached("retained", List.of("-Dbootui.agent.it.retain-context-loader=true"));

        assertThat(reached).containsKey(1);
    }

    @Test
    void anUnprivilegedInstallIsCaughtWhereTheJdkCapturesAccessControlContexts() throws Exception {
        Map<Integer, String> reached = reached("unprivileged", List.of("-Dbootui.agent.it.unprivileged=true"));

        if (Runtime.version().feature() < 24) {
            assertThat(reached).containsKey(1);
        } else {
            assertThat(reached).isEmpty();
        }
    }

    private static Map<Integer, String> reached(String name, List<String> options) throws Exception {
        Path dump = ChildJvm.WORK.resolve("leak-" + name + ".hprof");
        List<String> jvm = new ArrayList<>();
        jvm.add(ChildJvm.javaAgent(ChildJvm.TEST_AGENT));
        jvm.add("-Dbootui.agent.it.probe=bootuiagentit.run");
        jvm.add("-XX:SoftRefLRUPolicyMSPerMB=0");
        jvm.addAll(options);
        ChildJvm.Output output = ChildJvm.run(jvm, "runs", String.valueOf(RUNS), dump.toString());
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("INSTALLER"))
                .as(output.toString())
                .contains("failed=0")
                .contains("skipped=0");
        Map<Integer, String> reached =
                HeapWalk.read(dump).runsReachedByAgent("io/github/jdubois/bootui/agent/", RUN_CLASS);
        java.nio.file.Files.deleteIfExists(dump);
        return reached;
    }
}
