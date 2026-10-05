package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.core.dto.JavaAgentHookDto;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentRetransformationDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorToggleDto;
import io.github.jdubois.bootui.core.dto.JavaAgentSnippetDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        installer.put("retransformed", 99);
        stub.installer = installer;
        Map<String, Object> executors = new LinkedHashMap<>();
        executors.put("id", "executors");
        executors.put("state", "installed");
        executors.put("instrumentedTypes", 4);
        executors.put("failures", List.of("com.example.Broken: boom"));
        executors.put("transformed", 3);
        executors.put("retransformed", 12);
        executors.put("failed", 1);
        executors.put("skipped", 2);
        executors.put("retransformMillis", 45L);
        executors.put("installMillis", 50L);
        executors.put("selfTestMillis", 7L);
        executors.put("durationMillis", 57L);
        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("id", AgentSensorSettings.THREADS);
        threads.put("state", "installing");
        threads.put("retransformed", 5);
        threads.put("retransformMillis", 10L);
        stub.sensors = List.of(executors, threads);
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
        assertThat(armed.retransformation())
                .as("the production sensors' summed cost, never the test-only installer")
                .isEqualTo(new JavaAgentRetransformationDto("installing", 3, 17, 1, 2, 55L, true));
        assertThat(armed.sensors()).first().satisfies(sensor -> {
            assertThat(sensor.id()).isEqualTo("executors");
            assertThat(sensor.active()).isTrue();
            assertThat(sensor.instrumentedTypes()).isEqualTo(4);
            assertThat(sensor.failures()).containsExactly("com.example.Broken: boom");
            assertThat(sensor.transformedTypes()).isEqualTo(3);
            assertThat(sensor.retransformedTypes()).isEqualTo(12);
            assertThat(sensor.installMillis()).isEqualTo(50L);
            assertThat(sensor.selfTestMillis()).isEqualTo(7L);
            assertThat(sensor.durationMillis()).isEqualTo(57L);
            assertThat(sensor.retransformMillis()).isEqualTo(45L);
        });
        assertThat(armed.sensors().get(1).active())
                .as("the default claim does not use the threads sensor")
                .isFalse();
        assertThat(armed.warnings()).isEmpty();

        claim.get().disarm();
        JavaAgentReport disarmed = service.report();

        assertThat(disarmed.state()).isEqualTo(JavaAgentReport.DISARMED);
        assertThat(disarmed.reason()).isEqualTo(JavaAgentService.DISARMED_REASON);
        assertThat(disarmed.claim().armed()).isFalse();
        assertThat(disarmed.sensors()).noneMatch(JavaAgentSensorDto::active);

        threads.put("state", "released");
        executors.put("state", "released");
        assertThat(service.report().retransformation())
                .as("once every sensor is released, the summed cost no longer reads installed")
                .isEqualTo(new JavaAgentRetransformationDto("off", 3, 17, 1, 2, 55L, false));
        executors.put("state", "installed");
        assertThat(service.report().retransformation().state()).isEqualTo("installed");
        threads.put("state", "self-test-failed (release-failed)");
        assertThat(service.report().retransformation().state()).isEqualTo("failed");
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
                                "id", "ThreadPoolExecutor.addWorker",
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
                            org.assertj.core.api.Assertions.tuple("ThreadPoolExecutor.addWorker", "key", 1L),
                            org.assertj.core.api.Assertions.tuple("ThreadPoolExecutor.runWorker", "apply", 0L));
            assertThat(row.executors()).isNotNull();
            assertThat(row.executors().pending()).isEqualTo(1L);
            assertThat(row.executors().refused()).isZero();
        });

        claim.get().disarm();
        assertThat(service.recording()).isFalse();
    }

    @Test
    void eachSensorRowCarriesTheCountersTheBridgeKeepsUnderItsOwnId() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> executors = new LinkedHashMap<>();
        executors.put("id", "executors");
        executors.put("state", "installed");
        executors.put("hooks", List.of());
        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("id", AgentSensorSettings.THREADS);
        threads.put("state", "installed");
        threads.put(
                "hooks",
                List.of(Map.of(
                        "id", "Thread.start",
                        "kind", "key",
                        "type", "java.lang.Thread",
                        "present", true,
                        "transformed", true,
                        "selfTest", "passed")));
        stub.sensors = List.of(executors, threads);
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        JavaAgentReport report = service.report();

        assertThat(report.sensors()).hasSize(2);
        assertThat(report.sensors().get(0).executors().libraryThreadsSkipped())
                .as("the executors sensor has no thread counters")
                .isNull();
        assertThat(report.sensors().get(1)).satisfies(row -> {
            assertThat(row.id()).isEqualTo("threads");
            assertThat(row.hooks()).extracting(JavaAgentHookDto::id).containsExactly("Thread.start");
            assertThat(row.executors()).isNotNull();
            assertThat(row.executors().libraryThreadsSkipped()).isZero();
            assertThat(row.executors().poolWorkersSkipped()).isZero();
        });
    }

    @Test
    void theInventorySensorRowCarriesItsRecordHooksAndItsOwnCounters() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("id", AgentSensorSettings.INVENTORY);
        inventory.put("state", "installed");
        inventory.put("selfTestPassed", true);
        inventory.put(
                "hooks",
                List.of(
                        Map.of(
                                "id", "method entry",
                                "kind", "record",
                                "type", "(claimed packages)",
                                "present", true,
                                "transformed", true,
                                "selfTest", "passed"),
                        Map.of(
                                "id", "class load",
                                "kind", "record",
                                "type", "(every class)",
                                "present", true,
                                "transformed", true,
                                "selfTest", "not-exercised")));
        inventory.put("transformed", 3);
        stub.sensors = List.of(inventory);
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        io.github.jdubois.bootui.agent.bridge.CodeInventory.classLoaded("file:/libs/shop.jar", "com/shop/A");
        int greet = io.github.jdubois.bootui.agent.bridge.CodeInventory.methodId("com.example.Shop#greet()V");
        io.github.jdubois.bootui.agent.bridge.CodeInventory.methodId("com.example.Shop#never()V");
        io.github.jdubois.bootui.agent.bridge.CodeInventory.tracked(new int[] {greet, greet + 1});
        io.github.jdubois.bootui.agent.bridge.CodeInventory.hit(greet);

        JavaAgentReport report =
                service(Bridges.access(), settings("spring", true, null)).report();

        assertThat(stub.requests.get(0))
                .as("the default claim asks for the inventory sensor and the ring's capacity")
                .containsEntry(
                        "sensors", List.of("executors", "inventory", "code-paths", "processes", "network", "blocking"))
                .containsEntry("ringCapacity", AgentSensorSettings.DEFAULT_RING_CAPACITY);
        assertThat(report.sensors()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("inventory");
            assertThat(row.executors()).isNull();
            assertThat(row.hooks())
                    .extracting(JavaAgentHookDto::id, JavaAgentHookDto::kind, JavaAgentHookDto::fired)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("method entry", "record", 1L),
                            org.assertj.core.api.Assertions.tuple("class load", "record", 1L));
            assertThat(row.inventory()).isNotNull();
            assertThat(row.inventory().methodsTracked()).isEqualTo(2L);
            assertThat(row.inventory().executedThisRun()).isEqualTo(1L);
            assertThat(row.inventory().codeSources()).isEqualTo(1L);
            assertThat(row.inventory().methodOverflow()).isZero();
            assertThat(row.inventory().ringDropped()).isZero();
            assertThat(row.inventory().disabledReason()).isNull();
        });
    }

    @Test
    void theCodePathsSensorRowCarriesItsOwnCountersAndItsReasonWhenUnavailable() {
        JavaAgentService absent = service(AgentBridgeAccess.absent(), settings("spring", true, null));
        assertThat(absent.codePathsUnavailableReason())
                .startsWith(JavaAgentService.CODE_PATHS_REQUIREMENT + ": ")
                .contains("-javaagent");

        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> codePaths = new LinkedHashMap<>();
        codePaths.put("id", AgentSensorSettings.CODE_PATHS);
        codePaths.put("state", "installed");
        codePaths.put("selfTestPassed", true);
        codePaths.put("instrumentedTypes", 4);
        codePaths.put(
                "hooks",
                List.of(Map.of(
                        "id", "bean methods",
                        "kind", "record",
                        "type", "(bean classes)",
                        "present", true,
                        "transformed", true,
                        "selfTest", "passed")));
        codePaths.put("sharedTransformer", "inventory");
        stub.sensors = List.of(codePaths);
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        int id = io.github.jdubois.bootui.agent.bridge.CodeInventory.methodId("com.example.Shop#greet()V");
        io.github.jdubois.bootui.agent.bridge.CodePaths.exclude(
                claim.get().result().get("token") instanceof Long token ? token : -1L, id);

        JavaAgentReport report = service.report();

        assertThat(service.codePathsUnavailableReason()).isNull();
        assertThat(report.sensors()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("code-paths");
            assertThat(row.active()).isTrue();
            assertThat(row.instrumentedTypes()).isEqualTo(4);
            assertThat(row.executors()).isNull();
            assertThat(row.inventory()).isNull();
            assertThat(row.codePaths()).isNotNull();
            assertThat(row.codePaths().excludedMethods()).isEqualTo(1L);
            assertThat(row.codePaths().fragmentsFlushed()).isZero();
            assertThat(row.codePaths().disabledReason()).isNull();
            assertThat(row.hooks()).extracting(JavaAgentHookDto::id).containsExactly("bean methods");
        });
        assertThat(report.retransformation().durationMillis())
                .as("the shared transformer's time is never counted twice")
                .isZero();
    }

    @Test
    void codeInventoryNeedsAnArmedClaimAndTheInstalledInventorySensorNotDisabled() {
        JavaAgentService absent = service(AgentBridgeAccess.absent(), settings("spring", true, null));
        assertThat(absent.inventoryUnavailableReason())
                .startsWith(JavaAgentService.INVENTORY_REQUIREMENT + ": ")
                .contains("-javaagent");

        Bridges.StubAgent stub = Bridges.StubAgent.install();
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        assertThat(service.inventoryUnavailableReason())
                .as("attached, but not claimed")
                .startsWith(JavaAgentService.INVENTORY_REQUIREMENT + ": ")
                .contains("not claimed");

        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        assertThat(service.inventoryUnavailableReason())
                .as("armed, but the agent did not start the sensor")
                .contains("bootui.agent.sensors must include inventory");

        Map<String, Object> sensor = new LinkedHashMap<>();
        sensor.put("id", AgentSensorSettings.INVENTORY);
        sensor.put("state", "installing");
        stub.sensors = List.of(sensor);
        assertThat(service.inventoryUnavailableReason()).endsWith("the sensor is installing.");

        sensor.put("state", "installed");
        assertThat(service.inventoryUnavailableReason()).isNull();

        long generation = claim.get().generation();
        io.github.jdubois.bootui.agent.bridge.CodeInventory.disable(generation, false, "the self-test failed");
        try {
            assertThat(service.inventoryUnavailableReason()).endsWith("the self-test failed");
        } finally {
            io.github.jdubois.bootui.agent.bridge.CodeInventory.enable();
        }

        claim.get().disarm();
        assertThat(service.inventoryUnavailableReason()).as("a disarmed claim").isNotNull();
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

        sensor.put("state", "testing");
        assertThat(service.propagationUnavailableReason()).contains("self-test");
        assertThat(service.propagating()).isFalse();
        assertThat(service.report().retransformation().state()).isEqualTo("installed");
        assertThat(service.report().retransformation().running()).isFalse();

        sensor.put("state", "installed");
        assertThat(service.propagating()).as("an absent self-test verdict").isFalse();
        sensor.put("selfTestPassed", false);
        assertThat(service.propagationUnavailableReason()).contains("self-test");
        assertThat(service.propagating()).as("a pending self-test").isFalse();
        sensor.put("selfTestPassed", true);
        assertThat(service.propagationUnavailableReason()).isNull();
        assertThat(service.propagating()).isTrue();

        long generation = claim.get().generation();
        io.github.jdubois.bootui.agent.bridge.TaskPropagation.disable(generation, "the self-test failed");
        try {
            sensor.put("state", "self-test-failed");
            assertThat(service.propagationUnavailableReason()).endsWith("the self-test failed");
            assertThat(service.propagating()).isFalse();
            assertThat(service.report().retransformation().state()).isEqualTo("failed");
            sensor.put("state", "testing");
            assertThat(service.propagationUnavailableReason()).contains("have not passed their self-test yet");
        } finally {
            io.github.jdubois.bootui.agent.bridge.TaskPropagation.enable();
            sensor.put("state", "installed");
        }

        claim.get().detach();
        assertThat(service.propagationUnavailableReason()).contains("has not attached its executor handoffs");

        claim.get().attach(new AgentHandoffs(null, null, null));
        claim.get().disarm();
        assertThat(service.propagating()).as("a disarmed claim").isFalse();
    }

    @Test
    void aLaterClaimWithoutTheExecutorsSensorNeitherPropagatesNorShowsTheStillInstalledSensorActive() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> sensor = new LinkedHashMap<>();
        sensor.put("id", "executors");
        sensor.put("state", "installed");
        sensor.put("selfTestPassed", true);
        stub.sensors = List.of(sensor);
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        claim.get().attach(new AgentHandoffs(null, null, null));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        assertThat(service.propagating()).isTrue();
        assertThat(service.report().sensors())
                .singleElement()
                .satisfies(row -> assertThat(row.active()).isTrue());

        claim.get().disarm();
        claim.set(AgentClaim.claim(
                Bridges.access(),
                "petclinic",
                "petclinic@2",
                "dev",
                List.of("com.example"),
                new AgentSensorSettings(List.of(), List.of(), List.of(), null)));
        claim.get().attach(new AgentHandoffs(null, null, null));

        assertThat(service.recording()).isTrue();
        assertThat(service.propagating()).isFalse();
        assertThat(service.propagationUnavailableReason()).contains("this application's claim does not use it");
        assertThat(service.report().sensors()).singleElement().satisfies(row -> {
            assertThat(row.state()).as("the transformer stays installed (D34)").isEqualTo("installed");
            assertThat(row.active()).isFalse();
        });
    }

    @Test
    void anExecutorsSensorTheAgentReportsInactiveDoesNotPropagate() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> sensor = new LinkedHashMap<>();
        sensor.put("id", "executors");
        sensor.put("state", "installed");
        sensor.put("active", false);
        stub.sensors = List.of(sensor);
        claim.set(AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example")));
        claim.get().attach(new AgentHandoffs(null, null, null));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        assertThat(service.propagationUnavailableReason()).endsWith("inactive for this application's claim.");
        assertThat(service.report().sensors())
                .singleElement()
                .satisfies(row -> assertThat(row.active()).isFalse());
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

    @Test
    void theOptInSensorsAreSwitchedAtRunTimeOnlyWhileThisApplicationsClaimIsArmed() {
        JavaAgentService absent = service(AgentBridgeAccess.absent(), settings("spring", true, null));
        assertThat(absent.report().toggles()).isEmpty();
        assertThatThrownBy(() -> absent.switchSensor("environment", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not armed");

        Bridges.StubAgent stub = Bridges.StubAgent.install();
        AgentSensorSettings sensors =
                new AgentSensorSettings(List.of("processes", "files"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        List<Runnable> switched = new ArrayList<>();
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        service.onSensorSwitched(() -> switched.add(() -> {}));

        assertThat(service.report().toggles())
                .extracting(
                        JavaAgentSensorToggleDto::id,
                        JavaAgentSensorToggleDto::configured,
                        JavaAgentSensorToggleDto::enabled,
                        JavaAgentSensorToggleDto::overridden,
                        JavaAgentSensorToggleDto::state,
                        JavaAgentSensorToggleDto::available)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("threads", false, false, false, "off", true),
                        org.assertj.core.api.Assertions.tuple("files", true, true, false, "installing", true),
                        org.assertj.core.api.Assertions.tuple("environment", false, false, false, "off", true));
        assertThat(service.report().toggles())
                .allSatisfy(toggle -> assertThat(toggle.optInReason()).startsWith("Off by default"));

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("id", "environment");
        environment.put("state", "installed");
        stub.sensors = List.of(environment);
        JavaAgentReport on = service.switchSensor(" environment ", true);

        assertThat(on.toggles().get(2)).satisfies(toggle -> {
            assertThat(toggle.enabled()).isTrue();
            assertThat(toggle.overridden()).isTrue();
            assertThat(toggle.state()).isEqualTo("installed");
        });
        assertThat(claim.get().activeSensors()).containsExactly("processes", "files", "environment");
        assertThat(claim.get().sensors().sensors()).containsExactly("processes", "files");
        assertThat(stub.ops()).contains("sensors");
        assertThat(switched).hasSize(1);
        assertThat(service.sideEffectsCoverage("environment").toggle().enabled())
                .isTrue();

        JavaAgentReport off = service.switchSensor("files", false);
        assertThat(off.toggles().get(1)).satisfies(toggle -> {
            assertThat(toggle.configured()).isTrue();
            assertThat(toggle.enabled()).isFalse();
            assertThat(toggle.overridden()).isTrue();
            assertThat(toggle.state()).isEqualTo("off");
        });
        JavaAgentService.SideEffectsCoverage files = service.sideEffectsCoverage("files");
        assertThat(files.state()).isEqualTo("not-claimed");
        assertThat(files.reason()).startsWith("Switched off at run time");
        assertThat(service.sideEffectsCoverage("processes").toggle()).isNull();

        assertThatThrownBy(() -> service.switchSensor("executors", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads, files, environment");
        claim.get().disarm();
        assertThat(service.report().toggles()).isEmpty();
        assertThatThrownBy(() -> service.switchSensor("environment", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSwitchTheAgentFailedAfterTheBridgeCommittedItIsReportedAsMadeNotRefused() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        stub.sensorsAnswer = new LinkedHashMap<>(Map.of("status", "failed", "reason", "boom"));
        AgentSensorSettings sensors = new AgentSensorSettings(List.of("processes"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        JavaAgentReport report = service.switchSensor("environment", true);

        assertThat(report.toggles().get(2).enabled()).isTrue();
        assertThat(report.toggles().get(2).overridden()).isTrue();
        assertThat(report.toggles().get(2).state()).isEqualTo("failed");
        assertThat(report.toggles().get(2).failure()).isEqualTo("The agent failed this switch: boom");

        stub.sensorsAnswer = null;
        JavaAgentReport off = service.switchSensor("environment", false);
        assertThat(off.toggles().get(2).failure()).isNull();
        assertThat(off.toggles().get(2).state()).isEqualTo("off");
    }

    @Test
    void aSideEffectSensorThatFailedItsSelfTestInThisJvmCannotBeSwitchedBackOn() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("id", "files");
        files.put("state", "self-test-failed");
        stub.sensors = List.of(files);
        AgentSensorSettings sensors = new AgentSensorSettings(List.of("processes"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        assertThat(service.report().toggles().get(1)).satisfies(toggle -> {
            assertThat(toggle.available()).isFalse();
            assertThat(toggle.unavailableReason()).contains("until the JVM restarts");
        });
        assertThatThrownBy(() -> service.switchSensor("files", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed its self-test");
        assertThat(stub.ops()).doesNotContain("sensors");
    }

    @Test
    void aSwitchedOnSideEffectSensorStillReleasedReadsInstalling() {
        Bridges.StubAgent stub = Bridges.StubAgent.install();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("id", "environment");
        environment.put("state", "released");
        stub.sensors = List.of(environment);
        AgentSensorSettings sensors = new AgentSensorSettings(List.of("processes"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));

        assertThat(service.switchSensor("environment", true).toggles().get(2).state())
                .isEqualTo("installing");
    }

    @Test
    void theThreadsSwitchIsUnavailableInTheRunItFailedIn() {
        Bridges.StubAgent.install();
        AgentSensorSettings sensors = new AgentSensorSettings(List.of(), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        assertThat(service.report().toggles().get(0).available()).isTrue();

        io.github.jdubois.bootui.agent.bridge.ThreadPropagation.disable(
                claim.get().generation(), false);

        assertThat(service.report().toggles().get(0)).satisfies(toggle -> {
            assertThat(toggle.available()).isFalse();
            assertThat(toggle.unavailableReason()).contains("until the application restarts");
        });
    }

    @Test
    void aSwitchRefusedByTheBridgeIsAConflictWithItsReason() {
        Bridges.StubAgent.install();
        AgentSensorSettings sensors = new AgentSensorSettings(List.of("threads"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        io.github.jdubois.bootui.agent.bridge.ThreadPropagation.disable(
                claim.get().generation(), false);
        service.switchSensor("threads", false);

        assertThatThrownBy(() -> service.switchSensor("threads", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("until the application restarts");
        assertThat(claim.get().activeSensors()).isEmpty();
    }

    @Test
    void aRestartOfTheSameApplicationKeepsTheSwitchAndShowsTheConfiguredDefault() {
        Bridges.StubAgent.install();
        AgentSensorSettings sensors = new AgentSensorSettings(List.of("processes"), List.of(), List.of(), null);
        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@1", "dev", List.of("com.example"), sensors));
        JavaAgentService service = service(Bridges.access(), settings("spring", true, null));
        service.switchSensor("environment", true);
        claim.get().disarm();

        claim.set(
                AgentClaim.claim(Bridges.access(), "petclinic", "petclinic@2", "dev", List.of("com.example"), sensors));

        assertThat(claim.get().activeSensors()).containsExactly("processes", "environment");
        assertThat(claim.get().sensorOverrides()).containsExactly(Map.entry("environment", true));
        assertThat(service.report().toggles().get(2)).satisfies(toggle -> {
            assertThat(toggle.configured()).isFalse();
            assertThat(toggle.enabled()).isTrue();
            assertThat(toggle.overridden()).isTrue();
        });
    }

    private JavaAgentService service(AgentBridgeAccess access, JavaAgentSettings settings) {
        return new JavaAgentService(access, claim::get, settings);
    }

    private JavaAgentSettings settings(String stack, boolean enabled, String disabledReason) {
        return new JavaAgentSettings("1.19.0", stack, enabled, disabledReason, repository, repository, false);
    }
}
