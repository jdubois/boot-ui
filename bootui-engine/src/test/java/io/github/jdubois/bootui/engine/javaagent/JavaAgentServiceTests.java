package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.core.dto.JavaAgentHookDto;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentSnippetDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAgentServiceTests {

    @TempDir
    Path repository;

    private final AtomicReference<AgentClaim> claim = new AtomicReference<>();

    @BeforeEach
    @AfterEach
    void reset() {
        Bridges.reset();
    }

    @Test
    void withoutTheAgentTheReportSaysNotAttachedAndOffersEverySpringSnippet() {
        JavaAgentReport report = service(AgentBridgeAccess.absent(), settings("spring", true, null))
                .report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.NOT_ATTACHED);
        assertThat(report.reason()).isEqualTo(JavaAgentService.NOT_ATTACHED_REASON);
        assertThat(report.protocol()).isNull();
        assertThat(report.expectedProtocol()).isEqualTo(1);
        assertThat(report.agentVersion()).isNull();
        assertThat(report.bootUiVersion()).isEqualTo("1.19.0");
        assertThat(report.claim()).isNull();
        assertThat(report.counters()).isNull();
        assertThat(report.retransformation()).isNull();
        assertThat(report.sensors()).isEmpty();
        assertThat(report.jdk()).startsWith(Runtime.version().toString());
        assertThat(report.setup().jarFound()).isFalse();
        assertThat(report.setup().jarPath())
                .isEqualTo(repository
                        .resolve("com/julien-dubois/bootui/bootui-agent/1.19.0/bootui-agent-1.19.0.jar")
                        .toString());
        assertThat(report.setup().snippets())
                .extracting(JavaAgentSnippetDto::id)
                .containsExactly(
                        "maven-download",
                        "maven-plugin",
                        "gradle-kotlin",
                        "gradle-groovy",
                        "surefire",
                        "intellij",
                        "java-tool-options");
    }

    @Test
    void aDisabledReasonWinsAsInQuarkusProductionMode() {
        JavaAgentReport report = service(Bridges.access(), settings("quarkus", true, "Quarkus production mode"))
                .report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.DISABLED);
        assertThat(report.reason()).isEqualTo("Quarkus production mode");
        assertThat(report.setup().snippets())
                .extracting(JavaAgentSnippetDto::id)
                .contains("quarkus-dev");
    }

    @Test
    void aNativeImageIsUnavailable() {
        JavaAgentSettings nativeImage =
                new JavaAgentSettings("1.19.0", "spring", true, null, repository, repository, true);

        assertThat(service(AgentBridgeAccess.absent(), nativeImage).report().state())
                .isEqualTo(JavaAgentReport.UNAVAILABLE);
    }

    @Test
    void anotherProtocolIsUnavailableWithBothProtocols() {
        JavaAgentReport report = service(
                        new AgentBridgeAccess(AgentBridgeAccessTests.FutureBridge.class),
                        settings("spring", true, null))
                .report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.UNAVAILABLE);
        assertThat(report.protocol()).isEqualTo(2);
        assertThat(report.reason()).contains("protocol 2").contains("bootui-agent jar of BootUI 1.19.0");
    }

    @Test
    void aBridgeWhoseAgentDidNotStartIsUnavailableWithItsLastMessage() {
        AgentBridge.message("unavailable: cannot locate the bootui-agent jar");

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.UNAVAILABLE);
        assertThat(report.reason()).endsWith(": unavailable: cannot locate the bootui-agent jar");
        assertThat(report.messages()).containsExactly("unavailable: cannot locate the bootui-agent jar");
        assertThat(report.counters()).isNotNull();
    }

    @Test
    void anAttachedAgentThisApplicationDoesNotClaimIsDormant() {
        Bridges.StubAgent.install();

        JavaAgentReport disabled =
                service(Bridges.access(), settings("spring", false, null)).report();
        JavaAgentReport unclaimed =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(disabled.state()).isEqualTo(JavaAgentReport.DORMANT);
        assertThat(disabled.reason()).isEqualTo(JavaAgentService.NOT_ENABLED_REASON);
        assertThat(unclaimed.state()).isEqualTo(JavaAgentReport.DORMANT);
        assertThat(unclaimed.reason()).isEqualTo(JavaAgentService.NOT_CLAIMED_REASON);
        assertThat(disabled.agentVersion()).isEqualTo("1.19.0");
        assertThat(disabled.loadMode()).isEqualTo("javaagent");
        assertThat(disabled.jarPath()).isEqualTo("/tmp/bootui-agent-1.19.0.jar");
        assertThat(disabled.startupMicros()).isEqualTo(1234L);
        assertThat(disabled.setup().jarPath())
                .as("the attached agent's own jar is the one to use")
                .isEqualTo(Path.of("/tmp/bootui-agent-1.19.0.jar").toString());
    }

    @Test
    void thisApplicationsClaimIsArmedThenDisarmedWithItsClaimAndCounters() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> installer = new LinkedHashMap<>();
        installer.put("state", "installed");
        installer.put("transformed", 3);
        installer.put("retransformed", 12);
        installer.put("failed", 1);
        installer.put("skipped", 2);
        installer.put("durationMillis", 45L);
        installer.put("running", false);
        stub.installer = installer;
        stub.sensors = List.of(Map.of(
                "id",
                "executor-propagation",
                "state",
                "active",
                "instrumentedTypes",
                4,
                "failures",
                List.of("com.example.Broken: boom")));
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        JavaAgentReport armed = service.report();

        assertThat(armed.state()).isEqualTo(JavaAgentReport.ARMED);
        assertThat(armed.reason()).isNull();
        assertThat(armed.protocol()).isEqualTo(1);
        assertThat(armed.claim().owner()).isEqualTo("petclinic@1");
        assertThat(armed.claim().application()).isEqualTo("petclinic");
        assertThat(armed.claim().mode()).isEqualTo("dev");
        assertThat(armed.claim().packages()).containsExactly("com.example");
        assertThat(armed.claim().armed()).isTrue();
        assertThat(armed.claim().armedAt()).isPositive();
        assertThat(armed.counters().claims()).isEqualTo(1L);
        assertThat(armed.retransformation().retransformed()).isEqualTo(12);
        assertThat(armed.retransformation().durationMillis()).isEqualTo(45L);
        assertThat(armed.sensors()).singleElement().satisfies(sensor -> {
            assertThat(sensor.id()).isEqualTo("executor-propagation");
            assertThat(sensor.instrumentedTypes()).isEqualTo(4);
            assertThat(sensor.failures()).containsExactly("com.example.Broken: boom");
        });
        assertThat(armed.warnings()).isEmpty();

        claim.get().disarm();
        JavaAgentReport disarmed = service.report();

        assertThat(disarmed.state()).isEqualTo(JavaAgentReport.DISARMED);
        assertThat(disarmed.reason()).isEqualTo(JavaAgentService.DISARMED_REASON);
        assertThat(disarmed.claim().armed()).isFalse();
    }

    @Test
    void anotherApplicationsClaimHoldsTheAgentWithItsOwner() {
        Bridges.StubAgent.install();
        AgentClaim.claim(Bridges.access(), "tests", "tests@1", "test", List.of("com.example"));
        claim.set(AgentClaim.claim(Bridges.access(), "other", "other@1", "test", List.of("org.other")));

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.HELD);
        assertThat(report.heldBy()).isEqualTo("tests@1");
        assertThat(report.reason()).contains("tests@1 (test)");
        assertThat(report.claim().owner()).isEqualTo("tests@1");
    }

    @Test
    void aDevApplicationTakingOverThisTestRunShowsItAsTheHolder() {
        Bridges.StubAgent.install();
        claim.set(AgentClaim.claim(Bridges.access(), "tests", "tests@1", "test", List.of()));
        AgentClaim.claim(Bridges.access(), "app", "app@1", "dev", List.of());

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.HELD);
        assertThat(report.heldBy()).isEqualTo("app@1");
    }

    @Test
    void aClaimTheAgentFailedIsFailedWithTheAgentsReason() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        stub.claimAnswer = Map.of("status", "failed", "reason", "the redefinition listener failed");
        claim.set(AgentClaim.claim(Bridges.access(), "app", "app@1", "dev", List.of()));

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(report.state()).isEqualTo(JavaAgentReport.FAILED);
        assertThat(report.reason()).isEqualTo("the redefinition listener failed");
    }

    @Test
    void theExecutorsSensorRowCarriesItsSelfTestHooksAndCounters() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> sensor = new LinkedHashMap<>();
        sensor.put("id", "executors");
        sensor.put("state", "installed");
        sensor.put("durationMillis", 87L);
        sensor.put("selfTestPassed", true);
        sensor.put("selfTestError", null);
        sensor.put("selfTestSteps", new LinkedHashMap<>(Map.of("thread-pool", "passed")));
        sensor.put(
                "hooks",
                List.of(
                        Map.of(
                                "id", "ThreadPoolExecutor",
                                "kind", "key",
                                "type", "java.util.concurrent.ThreadPoolExecutor",
                                "present", true,
                                "transformed", true,
                                "selfTest", "passed"),
                        Map.of(
                                "id", "ThreadPoolExecutor.runWorker",
                                "kind", "apply",
                                "type", "java.util.concurrent.ThreadPoolExecutor",
                                "present", true,
                                "transformed", true,
                                "selfTest", "passed")));
        sensor.put("transformed", 6);
        sensor.put("failed", 0);
        sensor.put("skipped", 1);
        stub.sensors = List.of(sensor);
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        AgentHandoffs handoffs = new AgentHandoffs(null, null, null);
        claim.get().attach(handoffs);
        try (io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.Scope ignored =
                io.github.jdubois.bootui.engine.correlation.BootUiCorrelation.open(
                        io.github.jdubois.bootui.spi.CorrelationContext.forRequest("r1"))) {
            io.github.jdubois.bootui.agent.bridge.TaskPropagation.submitted(
                    new java.util.concurrent.FutureTask<>(() -> null),
                    io.github.jdubois.bootui.agent.bridge.TaskPropagation.KEY_THREAD_POOL);
        }
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        JavaAgentReport report = service.report();

        assertThat(service.recording()).isTrue();
        assertThat(report.sensors()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("executors");
            assertThat(row.state()).isEqualTo("installed");
            assertThat(row.instrumentedTypes()).isEqualTo(6);
            assertThat(row.skippedTypes()).isEqualTo(1);
            assertThat(row.durationMillis()).isEqualTo(87L);
            assertThat(row.selfTestPassed()).isTrue();
            assertThat(row.selfTestSteps()).containsEntry("thread-pool", "passed");
            assertThat(row.hooks())
                    .extracting(JavaAgentHookDto::id, JavaAgentHookDto::kind, JavaAgentHookDto::fired)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("ThreadPoolExecutor", "key", 1L),
                            org.assertj.core.api.Assertions.tuple("ThreadPoolExecutor.runWorker", "apply", 0L));
            assertThat(row.executors()).isNotNull();
            assertThat(row.executors().pending()).isEqualTo(1L);
            assertThat(row.executors().refused()).isZero();
        });

        claim.get().disarm();
        assertThat(service.recording()).isFalse();
    }

    @Test
    void propagationNeedsAnArmedClaimTheInstalledExecutorsSensorNoDisabledReasonAndAttachedHandoffs() {
        JavaAgentService absent = service(AgentBridgeAccess.absent(), settings("spring", true, null));
        assertThat(absent.propagating()).isFalse();
        assertThat(absent.propagationUnavailableReason())
                .startsWith(JavaAgentService.PROPAGATION_REQUIREMENT + ": ")
                .contains("-javaagent");

        Bridges.StubAgent stub = Bridges.StubAgent.install();
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        claim.get().attach(new AgentHandoffs(null, null, null));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        assertThat(service.recording()).isTrue();
        assertThat(service.propagationUnavailableReason())
                .as("armed, but without the executors sensor")
                .contains("bootui.agent.sensors");

        Map<String, Object> sensor = new LinkedHashMap<>();
        sensor.put("id", "executors");
        sensor.put("state", "installing");
        stub.sensors = List.of(sensor);
        assertThat(service.propagationUnavailableReason()).endsWith("the sensor is installing.");

        sensor.put("state", "installed");
        assertThat(service.propagationUnavailableReason()).isNull();
        assertThat(service.propagating()).isTrue();

        long generation = claim.get().generation();
        io.github.jdubois.bootui.agent.bridge.TaskPropagation.disable(generation, "the self-test failed");
        try {
            assertThat(service.propagationUnavailableReason()).endsWith("the self-test failed");
            assertThat(service.propagating()).isFalse();
        } finally {
            io.github.jdubois.bootui.agent.bridge.TaskPropagation.enable();
        }

        claim.get().detach();
        assertThat(service.propagationUnavailableReason()).contains("has not attached its executor handoffs");

        claim.get().attach(new AgentHandoffs(null, null, null));
        claim.get().disarm();
        assertThat(service.propagating()).as("a disarmed claim").isFalse();
    }

    @Test
    void onlyTheVerifiedJdksPassWithoutTheSelfTestWarning() {
        assertThat(JavaAgentService.verifiedJdk(17)).isTrue();
        assertThat(JavaAgentService.verifiedJdk(21)).isTrue();
        assertThat(JavaAgentService.verifiedJdk(25)).isTrue();
        assertThat(JavaAgentService.verifiedJdk(26)).isTrue();
        assertThat(JavaAgentService.verifiedJdk(27)).isTrue();
        assertThat(JavaAgentService.verifiedJdk(24)).isFalse();
        assertThat(JavaAgentService.UNVERIFIED_JDK_WARNING.formatted(24))
                .contains("not verified on JDK 24")
                .contains("self-test decides");
    }

    @Test
    void anAgentOfAnotherVersionOnTheSameProtocolIsAWarning() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        stub.version = "1.18.0";

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(report.warnings())
                .singleElement()
                .asString()
                .contains("1.18.0")
                .contains("1.19.0");
    }

    @Test
    void aJarInTheLocalRepositoryIsFoundAndNeedsNoDownload() throws Exception {
        Path jar = repository.resolve("com/julien-dubois/bootui/bootui-agent/1.19.0/bootui-agent-1.19.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});

        JavaAgentReport report = service(AgentBridgeAccess.absent(), settings("quarkus", true, null))
                .report();

        assertThat(report.setup().jarFound()).isTrue();
        assertThat(report.setup().snippets())
                .extracting(JavaAgentSnippetDto::id)
                .containsExactly("quarkus-dev", "surefire", "intellij", "java-tool-options");
    }

    private JavaAgentService service(AgentBridgeAccess access, JavaAgentSettings settings) {
        return new JavaAgentService(access, claim::get, settings);
    }

    private JavaAgentSettings settings(String stack, boolean enabled, String disabledReason) {
        return new JavaAgentSettings("1.19.0", stack, enabled, disabledReason, repository, repository, false);
    }
}
