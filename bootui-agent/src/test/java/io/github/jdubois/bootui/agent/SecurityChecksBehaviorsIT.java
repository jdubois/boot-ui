package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The security-sinks sensor's JDK checks in forked JVMs against the published jar (PLAN-v2 §5.16, M5-6b2), on every JDK
 * the agent's suite runs on: first the retransformation check of {@code MessageDigest}, {@code Cipher}, {@code
 * ObjectInputStream}, {@code SSLContext}, and {@code HttpsURLConnection}, each loaded and used before the claim, alone
 * and beside the files, processes, network, and blocking sensors; then the behaviors, with their counterexamples; beside
 * the OpenTelemetry agent in both orders, JaCoCo instrumenting the JDK's classes, and Mockito's {@code
 * mockStatic(MessageDigest.class)} before and after the claim.
 */
class SecurityChecksBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    static final List<String> HOOKS = List.of(
            "id=MessageDigest.getInstance, kind=record, type=java.security.MessageDigest, present=true,"
                    + " transformed=true, selfTest=passed",
            "id=Cipher.getInstance, kind=record, type=javax.crypto.Cipher, present=true, transformed=true,"
                    + " selfTest=passed",
            "id=ObjectInputStream.readObject, kind=record, type=java.io.ObjectInputStream, present=true,"
                    + " transformed=true, selfTest=passed",
            "id=ObjectInputStream.resolveClass, kind=record, type=java.io.ObjectInputStream, present=true,"
                    + " transformed=true, selfTest=passed",
            "id=SSLContext.init, kind=record, type=javax.net.ssl.SSLContext, present=true, transformed=true,"
                    + " selfTest=passed",
            "id=HttpsURLConnection.setDefaultHostnameVerifier, kind=record, type=javax.net.ssl.HttpsURLConnection,"
                    + " present=true, transformed=true, selfTest=passed",
            "id=HttpsURLConnection.setDefaultSSLSocketFactory, kind=record, type=javax.net.ssl.HttpsURLConnection,"
                    + " present=true, transformed=true, selfTest=passed");

    private static final List<String> REQUIRED = List.of(
            "the application's serial filter factory can still be set after the self-test",
            "MD5, AES/ECB, and DESede asked for by the application are recorded with its call site and request",
            "Cipher.getInstance(String, String), which calls getInstance(String, Provider), is recorded once",
            "SHA-256, SHA-512, AES/GCM/NoPadding, and RSA/ECB/PKCS1Padding are not recorded",
            "a library's SHA-1 is recorded apart, with the application frame above it",
            "the JDK's own MD5 (UUID.nameUUIDFromBytes) is not recorded, only counted",
            "an unfiltered deserialization records its top class, the classes read, and its call site",
            "a stream with a filter records nothing",
            "an application trust manager passed to SSLContext.init is recorded, the JDK's default is not",
            "the application's default hostname verifier is recorded with its lambda's class");

    @Test
    void theChecksRetransformTheJdkClassesAndPassTheirSelfTestsAlone() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "security-checks-behaviors", "check");

        assertSelfTest(output);
    }

    @Test
    void theChecksPassBesideTheFilesProcessesNetworkAndBlockingSensors() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "security-checks-behaviors", "beside");

        assertSelfTest(output);
        for (String sensor : List.of("files", "processes", "network", "blocking")) {
            assertThat(output.value("SELF_TEST_" + sensor))
                    .as(sensor + " " + output)
                    .startsWith("true null");
        }
        assertAllPass(output, REQUIRED);
    }

    @Test
    void everyBehaviorPasses() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "security-checks-behaviors", "behaviors");

        assertAllPass(output, REQUIRED);
        assertThat(output.text()).contains("  PASS release restores MessageDigest and SSLContext");
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "security-checks-behaviors", "behaviors"), REQUIRED);
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "security-checks-behaviors", "behaviors"), REQUIRED);
    }

    /**
     * Without {@code jdk.unsupported} ({@code --limit-modules}), the deserialization self-test cannot allocate its
     * stream: that group alone is off, with its reason; the other groups and request-value matching still run.
     */
    @Test
    void withoutJdkUnsupportedOnlyTheDeserializationGroupIsOff() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        "--limit-modules",
                        "java.base,java.instrument,java.logging,java.management,java.sql",
                        ChildJvm.javaAgent(ChildJvm.AGENT)),
                "security-checks-behaviors",
                "limited");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SECURITY_SINKS"))
                .as(output.toString())
                .contains("deserialization=off: self-test failed for [ObjectInputStream.readObject")
                .contains("weak-algorithms=on")
                .contains("trust-managers=on");
        assertThat(output.value("REQUEST_VALUES")).as(output.toString()).isEqualTo("true");
        assertThat(output.value("SENSOR")).as(output.toString()).contains("state=installed");
        assertAllPassLines(
                output,
                List.of(
                        "MD5, AES/ECB, and DESede asked for by the application are recorded with its call site",
                        "an application trust manager passed to SSLContext.init is recorded",
                        "with the deserialization group off, an unfiltered read records nothing"));
    }

    /**
     * The sensor switched on, off, and on at run time for a claim that did not ask for it (PLAN-v2 M5-14): its checks
     * and request-value matching follow the switch, and switching off removes its hooks.
     */
    @Test
    void theSensorSwitchesOnOffAndOnAtRunTime() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "security-checks-behaviors", "switch");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertAllPassLines(
                output,
                List.of(
                        "before the switch, an MD5 records nothing and request values are not held",
                        "switching security-sinks on installs and self-tests its checks in this run, which record an"
                                + " MD5",
                        "switching security-sinks off stops its recording and request values at once and removes its"
                                + " hooks, processes recording on",
                        "switching security-sinks on again records an MD5 again"));
    }

    /** A JVM-wide filter set through {@code -Djdk.serialFilter} is the stream's own filter: no row. */
    @Test
    void aJvmWideSerialFilterCountsAsAFilter() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of("-Djdk.serialFilter=maxdepth=20;maxrefs=1000", ChildJvm.javaAgent(ChildJvm.AGENT)),
                "security-checks-behaviors",
                "jvm-filter");

        assertSelfTest(output);
        assertAllPassLines(output, List.of("a read under a JVM-wide jdk.serialFilter records nothing"));
    }

    /** JaCoCo instrumenting the JDK's security classes too, before the agent: both transformers stay valid. */
    @Test
    void everyBehaviorPassesWithJacocoInstrumentingTheJdk() throws Exception {
        Path exec = ChildJvm.WORK.resolve("security-checks.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        "-javaagent:" + System.getProperty("jacoco.agent.jar") + "=destfile=" + exec
                                + ",inclbootstrapclasses=true,includes=java.security.*:javax.crypto.*:javax.net.ssl.*",
                        ChildJvm.javaAgent(ChildJvm.AGENT)),
                "security-checks-behaviors",
                "behaviors");

        assertAllPass(output, REQUIRED);
    }

    /** Mockito's {@code mockStatic(MessageDigest.class)} before and after the claim: stubbing works, then MD5 records. */
    @Test
    void besideMockitosMockStaticInBothOrders() throws Exception {
        String classPath = String.join(
                java.io.File.pathSeparator,
                System.getProperty("mockito.jar"),
                System.getProperty("bytebuddy.jar"),
                System.getProperty("bytebuddy.agent.jar"),
                System.getProperty("objenesis.jar"));
        for (String order : List.of("mockito-first", "bootui-first")) {
            ChildJvm.Output output = ChildJvm.runWithClassPath(
                    List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                    classPath,
                    "security-checks-behaviors",
                    order);

            assertThat(output.value("MOCKITO_" + order))
                    .as(order + " " + output)
                    .isEqualTo("ok");
            assertThat(output.value("MOCKITO_AFTER_" + order))
                    .as(order + " " + output)
                    .isEqualTo("MD5");
            assertAllPass(
                    output,
                    List.of("MD5, AES/ECB, and DESede asked for by the application are recorded with its call site"));
        }
    }

    private static void assertSelfTest(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        String selfTest = output.value("SELF_TEST_security-sinks");
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        for (String hook : HOOKS) {
            assertThat(selfTest).as(output.toString()).contains(hook);
        }
        assertThat(output.value("SECURITY_SINKS"))
                .as(output.toString())
                .contains("deserialization=on")
                .contains("weak-algorithms=on")
                .contains("trust-managers=on");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    private static void assertAllPassLines(ChildJvm.Output output, List<String> required) {
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    private static void assertAllPass(ChildJvm.Output output, List<String> required) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_security-sinks"))
                .as(output.toString())
                .startsWith("true null");
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }
}
