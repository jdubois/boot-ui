package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The thread-activity sensor in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5e): first the JDK
 * retransformation check of its hooks alone, run on every JDK the agent's suite runs on ({@code Thread}, {@code
 * ThreadPoolExecutor}, {@code ForkJoinPool}, and, from JDK 21, {@code VirtualThread} and {@code ThreadPerTaskExecutor},
 * loaded before the claim, transformed, and passing their self-tests); then its behaviors, alone, beside M5-2's
 * {@code executors} and {@code threads} sensors on the same classes, and beside the OpenTelemetry agent in both orders:
 * a thread or an executor a request's application code left running is reported, one joined or shut down never is,
 * pool workers, the JDK's threads, and a library's never are, an executor never shut down is reclaimed without the
 * sensor keeping it, and BootUI's own work is never recorded.
 */
class ThreadActivityBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a thread the application started for a request and still running when it ended is reported left running",
            "a thread joined before its request ended is recorded started, never left running",
            "an executor created for a request and not shut down when it ended is reported left running",
            "an executor shut down in finally is recorded created and shut down, never left running",
            "fork-join workers and the common pool's are never threads of their own",
            "a thread the JDK starts for the application is the JDK's",
            "a java.util.Timer the application starts for a request is its own thread, left running",
            "a library's thread and executor on a request's thread are the library's",
            "a lazy holder's executor created in its static initializer inside a request is a singleton's",
            "a lazy Spring singleton bean's executor created on first use inside a request is a singleton's",
            "a Spring prototype bean's executor created for a request and never shut down is still left running",
            "an ArC singleton bean's executor created on first use inside a request is a singleton's",
            "starts no request owns are counted under the starting thread's family",
            "the sensor never keeps an executor",
            "BootUI's own threads and executors are never recorded");

    /**
     * Spring's bean factory and ArC, from this test's class path, for the lazy singleton counterexamples: the child's
     * class path holds the test classes only otherwise.
     */
    private static final String CONTAINERS = java.util.Arrays.stream(
                    System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .filter(entry -> {
                String name = java.nio.file.Path.of(entry).getFileName().toString();
                return name.endsWith(".jar")
                        && java.util.stream.Stream.of(
                                        "spring-beans-",
                                        "spring-core-",
                                        "commons-logging-",
                                        "jspecify-",
                                        "arc-",
                                        "jakarta.enterprise.",
                                        "jakarta.inject-",
                                        "jakarta.annotation-",
                                        "jakarta.transaction-",
                                        "jboss-logging-",
                                        "mutiny-",
                                        "smallrye-")
                                .anyMatch(name::startsWith);
            })
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));

    private static ChildJvm.Output run(List<String> jvm, String mode) throws Exception {
        return ChildJvm.runWithClassPath(jvm, CONTAINERS, "thread-activity-behaviors", mode);
    }

    private static final String VIRTUAL = "a virtual thread a request started is recorded virtual and left running";

    @Test
    void theThreadActivityHooksRetransformTheirJdkClassesAndPassTheirSelfTests() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        String selfTest = output.value("SELF_TEST_thread-activity");
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        for (String hook : List.of(
                "Thread.start",
                "ThreadPoolExecutor.addWorker",
                "ThreadPoolExecutor.<init>",
                "ThreadPoolExecutor.shutdown",
                "ThreadPoolExecutor.shutdownNow",
                "ForkJoinPool.<init>",
                "ForkJoinPool.shutdown")) {
            assertThat(selfTest)
                    .as(hook + " in " + output)
                    .contains("id=" + hook + ", kind=")
                    .containsPattern("id=" + java.util.regex.Pattern.quote(hook)
                            + ", kind=\\w+, type=[^,]+, present=true, transformed=true, selfTest=passed");
        }
        String since21 = Runtime.version().feature() >= 21 ? "passed" : "unsupported";
        for (String hook : List.of(
                "VirtualThread.start",
                "ThreadPerTaskExecutor.start",
                "ThreadPerTaskExecutor.<init>",
                "ThreadPerTaskExecutor.shutdown")) {
            assertThat(selfTest)
                    .as(hook + " in " + output)
                    .containsPattern("id=" + java.util.regex.Pattern.quote(hook) + ", .*?selfTest=" + since21);
        }
        assertThat(output.value("SENSOR")).as(output.toString()).contains("state=installed");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyThreadActivityBehaviorPasses() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "behaviors"));
    }

    /** M5-2's executors and threads sensors transform Thread and the executors too: both keep working, both orders. */
    @Test
    void everyThreadActivityBehaviorPassesBesideThePropagationSensors() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "beside-propagation");
        assertAllPass(output);
        assertThat(output.value("SELF_TEST_executors")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SELF_TEST_threads")).as(output.toString()).startsWith("true null");
    }

    @Test
    void everyThreadActivityBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "behaviors"));
    }

    @Test
    void everyThreadActivityBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "behaviors"));
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_thread-activity"))
                .as(output.toString())
                .startsWith("true null");
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        List<String> required = new ArrayList<>(REQUIRED);
        if (Runtime.version().feature() >= 21) {
            required.add(VIRTUAL);
        }
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }
}
