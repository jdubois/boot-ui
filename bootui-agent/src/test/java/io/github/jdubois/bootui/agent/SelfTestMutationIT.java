package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Mutation tests of the sensors' self-tests (PLAN-v2 M5-1, M5-2, D32): the test agent leaves one hook out of the
 * transformer while its siblings stay, and that hook's own self-test must fail, rather than pass on a sibling's count.
 */
class SelfTestMutationIT {

    @Test
    void everyHookPassesWithNothingOmitted() throws Exception {
        ChildJvm.Output output = run("");

        for (String hook : List.of(
                "ThreadPoolExecutor.addWorker",
                "ThreadPoolExecutor.queue",
                "ThreadPoolExecutor.runWorker",
                "CompletableFuture.AsyncSupply",
                "CompletableFuture.AsyncRun",
                "Thread.start",
                "Thread.run")) {
            assertThat(selfTest(output, hook)).as("%s: %s", hook, output).isEqualTo("passed");
        }
        if (Runtime.version().feature() >= 21) {
            assertThat(selfTest(output, "VirtualThread.run"))
                    .as(output.toString())
                    .isEqualTo("passed");
        }
        assertThat(output.value("EXECUTORS")).as(output.toString()).contains("asyncApplies=true");
        assertThat(output.value("APPLIED"))
                .as(output.toString())
                .contains("request-1 CompletableFuture.AsyncSupply")
                .contains("request-1 CompletableFuture.AsyncRun");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ThreadPoolExecutor.addWorker", "ThreadPoolExecutor.queue", "ThreadPoolExecutor.runWorker"})
    void aMissingCoreThreadPoolHookFailsItsOwnSelfTestAndDisablesPropagation(String omitted) throws Exception {
        ChildJvm.Output output = run(omitted);

        assertThat(selfTest(output, omitted)).as(output.toString()).isEqualTo("failed");
        for (String sibling :
                List.of("ThreadPoolExecutor.addWorker", "ThreadPoolExecutor.runWorker", "ForkJoinTask.doExec")) {
            if (!sibling.equals(omitted)) {
                assertThat(selfTest(output, sibling))
                        .as("%s: %s", sibling, output)
                        .isEqualTo("passed");
            }
        }
        assertThat(output.value("SENSOR_executors")).as(output.toString()).contains("selfTestPassed=false");
        assertThat(output.value("EXECUTORS")).as(output.toString()).contains("self-test failed for [" + omitted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CompletableFuture.AsyncSupply", "CompletableFuture.AsyncRun"})
    void aMissingAsyncHookFailsAloneAndThePoolKeepsApplyingItsTasks(String omitted) throws Exception {
        ChildJvm.Output output = run(omitted);
        String sibling = omitted.endsWith("Supply") ? "CompletableFuture.AsyncRun" : "CompletableFuture.AsyncSupply";

        assertThat(selfTest(output, omitted)).as(output.toString()).isEqualTo("failed");
        assertThat(selfTest(output, sibling)).as(output.toString()).isEqualTo("passed");
        assertThat(selfTest(output, "ThreadPoolExecutor.runWorker"))
                .as(output.toString())
                .isEqualTo("passed");
        assertThat(output.value("SENSOR_executors")).as(output.toString()).contains("selfTestPassed=true");
        String flag = omitted.endsWith("Supply") ? "asyncSupplyApplies" : "asyncRunApplies";
        String siblingFlag = omitted.endsWith("Supply") ? "asyncRunApplies" : "asyncSupplyApplies";
        assertThat(output.value("EXECUTORS"))
                .as(output.toString())
                .contains(flag + "=false")
                .contains(siblingFlag + "=true");
        assertThat(output.value("APPLIED"))
                .as("the pool's own apply hook ran the task its class's hook could not: %s", output)
                .contains("request-1 ThreadPoolExecutor.runWorker")
                .contains("request-1 " + sibling);
    }

    @Test
    void aMissingThreadStartFailsTheThreadsSensor() throws Exception {
        ChildJvm.Output output = run("Thread.start");

        assertThat(selfTest(output, "Thread.start")).as(output.toString()).isEqualTo("failed");
        assertThat(output.value("SENSOR_threads")).as(output.toString()).contains("selfTestPassed=false");
    }

    @Test
    void aMissingVirtualThreadRunFailsTheThreadsSensor() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 21, "virtual threads");
        ChildJvm.Output output = run("VirtualThread.run");

        assertThat(selfTest(output, "VirtualThread.run")).as(output.toString()).isEqualTo("failed");
        assertThat(selfTest(output, "VirtualThread.start"))
                .as(output.toString())
                .isEqualTo("passed");
        assertThat(output.value("SENSOR_threads")).as(output.toString()).contains("selfTestPassed=false");
    }

    @Test
    void theProcessesHookPassesWithNothingOmitted() throws Exception {
        ChildJvm.Output output = run("", "processes");

        assertThat(selfTest(output, "ProcessBuilder.start"))
                .as(output.toString())
                .isEqualTo("passed");
        assertThat(output.value("SENSOR_processes"))
                .as(output.toString())
                .contains("state=installed")
                .contains("selfTestPassed=true");
    }

    @Test
    void aMissingProcessBuilderStartFailsTheProcessesSensorAndRemovesItsTransformer() throws Exception {
        ChildJvm.Output output = run("ProcessBuilder.start", "processes");

        assertThat(selfTest(output, "ProcessBuilder.start"))
                .as(output.toString())
                .isEqualTo("failed");
        assertThat(output.value("SENSOR_processes"))
                .as(output.toString())
                .contains("state=self-test-failed")
                .contains("selfTestPassed=false")
                .contains("self-test failed for [ProcessBuilder.start]");
    }

    @Test
    void everyNetworkHookPassesBesideTheProcessesHook() throws Exception {
        ChildJvm.Output output = run("", "processes,network");

        for (String hook : NetworkBehaviorsIT.HOOKS) {
            assertThat(selfTest(output, hook)).as("%s: %s", hook, output).isEqualTo("passed");
        }
        assertThat(output.value("SENSOR_network"))
                .as(output.toString())
                .contains("state=installed")
                .contains("selfTestPassed=true")
                .contains("hooksLeftOut=[]");
    }

    /** M5-5b: an optional hook that fails is left out, and its sensor keeps recording with the others. */
    @Test
    void aMissingOptionalNetworkHookIsLeftOutAndTheSensorKeepsRecording() throws Exception {
        ChildJvm.Output output = run("InetAddress.lookup", "processes,network");

        assertThat(selfTest(output, "InetAddress.lookup")).as(output.toString()).isEqualTo("failed");
        assertThat(selfTest(output, "Socket.connect")).as(output.toString()).isEqualTo("passed");
        assertThat(output.value("SENSOR_network"))
                .as(output.toString())
                .contains("state=installed")
                .contains("selfTestPassed=true")
                .contains("hooksLeftOut=[InetAddress.lookup]");
        assertThat(output.value("SENSOR_processes"))
                .as(output.toString())
                .contains("state=installed")
                .contains("selfTestPassed=true");
    }

    /** M5-5b: a core hook that fails takes its own sensor down, never another one. */
    @Test
    void aMissingCoreNetworkHookFailsTheNetworkSensorAloneAndProcessesKeepsRecording() throws Exception {
        ChildJvm.Output output = run("Socket.connect", "processes,network");

        assertThat(selfTest(output, "Socket.connect")).as(output.toString()).isEqualTo("failed");
        assertThat(output.value("SENSOR_network"))
                .as(output.toString())
                .contains("state=self-test-failed")
                .contains("selfTestPassed=false")
                .contains("self-test failed for [Socket.connect]");
        assertThat(output.value("SENSOR_processes"))
                .as(output.toString())
                .contains("state=installed")
                .contains("selfTestPassed=true");
        assertThat(selfTest(output, "ProcessBuilder.start"))
                .as(output.toString())
                .isEqualTo("passed");
    }

    private static ChildJvm.Output run(String omitted) throws Exception {
        return run(omitted, "executors,threads");
    }

    private static ChildJvm.Output run(String omitted, String sensors) throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.TEST_AGENT), "-Dbootui.agent.it.omit=" + omitted),
                "self-test",
                sensors);
        assertThat(output.exitCode()).as(output.toString()).isZero();
        return output;
    }

    /** The self-test result the agent reported for {@code hook}, from either sensor. */
    private static String selfTest(ChildJvm.Output output, String hook) {
        Matcher matcher = Pattern.compile(
                        "id=" + Pattern.quote(hook) + ", kind=[a-z]+, type=[^,]+, present=(?:true|false),"
                                + " transformed=(?:true|false), selfTest=([^}]+)}")
                .matcher(output.text());
        return matcher.find() ? matcher.group(1) : null;
    }
}
