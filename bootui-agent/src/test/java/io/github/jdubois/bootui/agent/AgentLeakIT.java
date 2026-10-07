package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The agent-rooted leak walk (PLAN-v2 M5-0 second pass, M5-1 acceptance): ten simulated DevTools runs, each in its own
 * child-first class loader, claim and disarm with the probe and the executors, threads, inventory, and code-paths sensors
 * installed;
 * afterwards the agent must strongly reach no run's class loader. Each mutation must be caught, so the test can fail.
 */
class AgentLeakIT {

    private static final int RUNS = 10;
    private static final String RUN_CLASS = "bootuiagentit/run/RunApp";

    private static final List<String> JVM_WIDE_THREADS = List.of(
            "ForkJoinPool.commonPool-worker",
            "CompletableFutureDelayScheduler",
            "ForkJoinPool.commonPool-delayScheduler",
            "shared-pool-worker");

    @Test
    void theAgentReachesNoRunAfterTenRuns() throws Exception {
        Map<Integer, String> reached = reached("clean", List.of());

        assertThat(reached).as("runs the agent strongly reaches: %s", reached).isEmpty();
    }

    /**
     * The thread-locals sensor (M5-5f) beside them: each run's pool task leaves a thread local set on a JVM-wide pool's
     * worker, which the sensor reports and whose holder it resolves into the run's class; afterwards the agent must
     * strongly reach no run, its thread local, or its value.
     */
    @Test
    void theThreadLocalsSensorKeepsNoRunAfterReportingAndResolvingItsThreadLocals() throws Exception {
        Path dump = ChildJvm.WORK.resolve("leak-thread-locals-sensor.hprof");
        List<String> jvm = new ArrayList<>();
        jvm.add(ChildJvm.javaAgent(ChildJvm.TEST_AGENT));
        jvm.add("-Dbootui.agent.it.probe=bootuiagentit.run");
        jvm.add("-Dbootui.agent.it.run-jar=" + TestJars.jar("leak-runs.jar", "bootuiagentit/run", List.of()));
        jvm.add("-Dbootui.agent.it.thread-locals=true");
        jvm.add("-XX:SoftRefLRUPolicyMSPerMB=0");
        ChildJvm.Output output = ChildJvm.run(jvm, "runs", String.valueOf(RUNS), dump.toString());
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("THREAD_LOCALS")).as(output.toString()).contains("errors=0");
        assertThat(Integer.parseInt(output.value("THREAD_LOCALS_RESOLVED")))
                .as("the runs' leaked thread locals were reported and resolved: %s", output)
                .isPositive();

        Map<Integer, String> reached =
                HeapWalk.read(dump).runsReachedByAgent("io/github/jdubois/bootui/agent/", RUN_CLASS);
        java.nio.file.Files.deleteIfExists(dump);

        assertThat(reached).as("runs the agent strongly reaches: %s", reached).isEmpty();
    }

    @Test
    void jvmWideThreadsKeepNoRunsContextAfterPropagatedWork() throws Exception {
        Path dump = dump("thread-locals", List.of());
        Map<Integer, String> reached = HeapWalk.read(dump)
                .runsReachedThroughThreadLocals(JVM_WIDE_THREADS, "io/github/jdubois/bootui/agent/", RUN_CLASS);
        java.nio.file.Files.deleteIfExists(dump);

        assertThat(reached)
                .as("runs JVM-wide threads keep through thread-locals: %s", reached)
                .isEmpty();
    }

    @Test
    void aReopenedContextLeftSetOnAJvmWideThreadIsCaught() throws Exception {
        Path dump = dump("left-set", List.of("-Dbootui.agent.it.leave-context-set=true"));
        Map<Integer, String> reached = HeapWalk.read(dump)
                .runsReachedThroughThreadLocals(JVM_WIDE_THREADS, "io/github/jdubois/bootui/agent/", RUN_CLASS);
        java.nio.file.Files.deleteIfExists(dump);

        assertThat(reached).isNotEmpty();
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
        Path dump = dump(name, options);
        Map<Integer, String> reached =
                HeapWalk.read(dump).runsReachedByAgent("io/github/jdubois/bootui/agent/", RUN_CLASS);
        java.nio.file.Files.deleteIfExists(dump);
        return reached;
    }

    private static Path dump(String name, List<String> options) throws Exception {
        Path dump = ChildJvm.WORK.resolve("leak-" + name + ".hprof");
        List<String> jvm = new ArrayList<>();
        jvm.add(ChildJvm.javaAgent(ChildJvm.TEST_AGENT));
        jvm.add("-Dbootui.agent.it.probe=bootuiagentit.run");
        // From a jar, outside a test root, so the inventory sensor instruments the runs' classes too.
        jvm.add("-Dbootui.agent.it.run-jar=" + TestJars.jar("leak-runs.jar", "bootuiagentit/run", List.of()));
        jvm.add("-XX:SoftRefLRUPolicyMSPerMB=0");
        jvm.addAll(options);
        ChildJvm.Output output = ChildJvm.run(jvm, "runs", String.valueOf(RUNS), dump.toString());
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_executors")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SELF_TEST_threads")).as(output.toString()).startsWith("true null");
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("INSTALLER"))
                .as(output.toString())
                .contains("failed=0")
                .contains("skipped=0");
        assertThat(output.value("EXECUTORS"))
                .as("the runs' work was propagated: %s", output)
                .doesNotContain("ThreadPoolExecutor.runWorker=0");
        assertThat(output.value("INVENTORY"))
                .as("the inventory sensor instrumented and saw the runs' classes: %s", output)
                .doesNotContain("methodsTracked=0,")
                .doesNotContain("executedThisRun=0,")
                .contains("methodsFailed=0,");
        assertThat(output.value("CODE_PATHS"))
                .as("the code-paths sensor recorded the runs' bean calls: %s", output)
                .doesNotContain("fragmentsFlushed=0,")
                .contains("errors=0,");
        return dump;
    }
}
