package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.SideEffectsSample;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunBaselineFile;
import io.github.jdubois.bootui.engine.journal.RunSideEffects;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sideeffectsapp.Launcher;

/**
 * M5-7b: a run's side-effect keys, frozen when it ends, and whether each sensor recorded the whole run, against the
 * real bridge class driven from an application class, as {@link SideEffectsServiceTests} does.
 */
class SideEffectsRunTests {

    private static final String REQUEST = "00000000000000ab";
    private static final String AWS_KEY = "AKIAABCDEFGHIJKLMNOP";

    @TempDir
    Path directory;

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean routesVisible = new AtomicBoolean(true);
    private final AtomicBoolean panelVisible = new AtomicBoolean(true);
    private final Map<String, String> routes = new LinkedHashMap<>();
    private final AgentEvidence evidence = new AgentEvidence(
            panel -> panel.equals(BootUiPanels.HTTP_EXCHANGES) ? routesVisible.get() : panelVisible.get(), null);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicBoolean recordingNow = new AtomicBoolean(true);
    private final AtomicBoolean recordingAtArm = new AtomicBoolean(true);
    private SideEffectsService service;
    private AgentClaim claim;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        clock.set(System.currentTimeMillis() - 60_000L);
    }

    @AfterEach
    void resetAgent() {
        if (service != null) {
            service.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aRunsKeysNameItsRoutesWithTheirTargetsButNeverAThreadFamily() throws Exception {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("/usr/bin/git", "--token", "secret-token");
        context.set(CorrelationContext.NONE);
        Thread worker = new Thread(() -> Launcher.failedStart("thread-tool"), "report-pool-7-thread-12");
        worker.start();
        worker.join();

        RunSideEffects run = service.runSideEffects();

        assertThat(run.unavailableReason()).isNull();
        assertThat(run.routesHidden()).isFalse();
        assertThat(run.keys()).singleElement().satisfies(key -> {
            assertThat(key.sensor()).isEqualTo("processes");
            assertThat(key.kind()).isEqualTo("process");
            assertThat(key.target()).isEqualTo("git");
            assertThat(key.scope()).isEqualTo("route");
            assertThat(key.owner()).isEqualTo("GET /reports");
            assertThat(key.count()).isEqualTo(1L);
        });
        assertThat(run.sensors())
                .extracting(RunSideEffects.Sensor::id)
                .containsExactly("network", "files", "processes", "environment");
        assertThat(run.sensors()).allSatisfy(sensor -> {
            assertThat(sensor.reason()).isNull();
            assertThat(sensor.startupReason()).isNull();
            assertThat(sensor.omittedKeys()).isZero();
        });
        assertThat(run.toString()).doesNotContain("secret-token", "--token", "/usr/bin");
    }

    @Test
    void workBeforeTheApplicationWasReadyIsStartupsKey() {
        clock.set(System.currentTimeMillis() + 60_000L);
        start();
        Launcher.failedStart("migrate-tool");

        assertThat(service.runSideEffects().keys()).singleElement().satisfies(key -> {
            assertThat(key.scope()).isEqualTo("startup");
            assertThat(key.owner()).isEqualTo("startup");
            assertThat(key.target()).isEqualTo("migrate-tool");
        });
    }

    @Test
    void aSensorTheAgentInstalledAfterTheClaimLeavesOnlyItsStartupIncomplete() {
        recordingAtArm.set(false);
        start();

        RunSideEffects run = service.runSideEffects();

        assertThat(run.sensor("processes").reason()).isNull();
        assertThat(run.sensor("processes").startupReason())
                .isEqualTo("the agent was still installing it when the application started, so the start of the run"
                        + " went unrecorded");
    }

    @Test
    void recordsTheAgentDroppedAClearOrASwitchMakeTheRunNotWhole() {
        start();
        dropped.set(3);
        assertThat(service.runSideEffects().sensor("processes").reason())
                .isEqualTo("the agent dropped 3 records: its ring was full");
        dropped.set(0);

        service.sensorSwitched("files");
        assertThat(service.runSideEffects().sensor("files").reason()).isEqualTo("it was switched during the run");

        refused.set(2);
        assertThat(service.runSideEffects().sensor("environment").reason())
                .isEqualTo("the agent's string table refused 2 strings, so records lost a call site, a thread, or a"
                        + " target");
        refused.set(0);

        evidence.clear();
        assertThat(service.runSideEffects().sensor("network").reason())
                .isEqualTo("the recording was cleared during the run");
    }

    @Test
    void anotherThreadsWorkBeforeTheApplicationWasReadyIsNoStartupKey() throws Exception {
        clock.set(System.currentTimeMillis() + 60_000L);
        start();
        Thread consumer = new Thread(() -> Launcher.failedStart("consumer-tool"), "kafka-consumer-1");
        consumer.start();
        consumer.join();
        Launcher.failedStart("migrate-tool");

        assertThat(service.runSideEffects().keys())
                .extracting(RunSideEffects.Key::target)
                .containsExactly("migrate-tool");
        assertThat(service.sensor("processes", null, null).rows())
                .as("the panel still shows both as startup's")
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.scope()).isEqualTo(SideEffectsRowDto.STARTUP));
    }

    @Test
    void aSensorNotRecordingAtTheEndOrNotClaimedSaysSo() {
        start(List.of("processes", "network"));
        recordingNow.set(false);

        RunSideEffects run = service.runSideEffects();

        assertThat(run.sensor("files").reason())
                .isEqualTo("it was not claimed: bootui.agent.sensors does not include files");
        assertThat(run.sensor("processes").reason()).isEqualTo("it was not recording when the run ended");
    }

    @Test
    void aRequestWhoseRouteIsNotNamedYetIsOmittedNeverAKey() {
        start();
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("git");
        context.set(CorrelationContext.NONE);

        RunSideEffects run = service.runSideEffects();

        assertThat(run.keys()).isEmpty();
        assertThat(run.sensor("processes").omittedKeys()).isEqualTo(1L);
    }

    @Test
    void whileHttpExchangesIsHiddenRoutesAreOneHiddenOwner() {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("git");
        context.set(CorrelationContext.NONE);
        routesVisible.set(false);

        RunSideEffects run = service.runSideEffects();

        assertThat(run.routesHidden()).isTrue();
        assertThat(run.keys())
                .singleElement()
                .satisfies(key -> assertThat(key.owner()).isEqualTo(SideEffectsStore.ROUTE_HIDDEN));

        panelVisible.set(false);
        assertThat(service.runSideEffects().unavailableReason()).startsWith("Side Effects was not shown");
    }

    @Test
    void endingTheRunFreezesItsSideEffectsForItsSummary() {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("git");
        context.set(CorrelationContext.NONE);

        service.endRun();
        RunSideEffects ended = service.runSideEffects();
        recordingNow.set(false);
        Launcher.failedStart("later-tool");
        service.endRun();
        service.close();

        assertThat(service.runSideEffects()).isSameAs(ended);
        assertThat(ended.keys()).extracting(RunSideEffects.Key::target).containsExactly("git");
        assertThat(ended.sensor("processes").reason()).isNull();
    }

    @Test
    void theBaselineFileHoldsNamesAndMaskedPatternsNeverASecretLookingSegment() throws IOException {
        start();
        routes.put(REQUEST, "GET /reports");
        System.setProperty(AWS_KEY, "the-value-itself");
        try {
            context.set(CorrelationContext.forRequest(REQUEST));
            Launcher.writeFile("/srv/keys/" + AWS_KEY + "/export.csv");
            Launcher.readProperty(AWS_KEY);
            Launcher.failedStart("/opt/tools/report-tool", "--password", "hunter2-secret");
            context.set(CorrelationContext.NONE);
        } finally {
            System.clearProperty(AWS_KEY);
        }
        service.endRun();
        RunSideEffects run = service.runSideEffects();
        assertThat(run.keys())
                .extracting(RunSideEffects.Key::target)
                .contains("/srv/keys/******/export.csv", "******", "report-tool");

        Path path = directory.resolve("baseline.bin");
        new RunBaselineFile(path, "shop")
                .write(RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), null, run, 2));
        String written = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);

        assertThat(written)
                .contains("GET /reports", "report-tool", "/srv/keys/******/export.csv")
                .doesNotContain(AWS_KEY, "the-value-itself", "hunter2-secret", "--password", "/opt/tools");
        assertThat(new RunBaselineFile(path, "shop").read().summary().sideEffects())
                .isEqualTo(run);
    }

    private void start() {
        start(List.of("processes", "network", "files", "environment"));
    }

    private void start(List<String> sensors) {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("sideeffectsapp"),
                new AgentSensorSettings(
                        sensors, List.of(), List.of(), null, AgentSensorSettings.DEFAULT_RING_CAPACITY));
        claim.attach(new AgentHandoffs(context::get, null, null));
        SideEffects.enable(SideEffects.MASK_PROCESSES | SideEffects.MASK_FILES | SideEffects.MASK_ENVIRONMENT);
        service = new SideEffectsService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(SideEffectsSensorDto.RECORDING, null, List.of(), 0L),
                evidence,
                clock::get,
                "/home/someone");
        service.setSamplers(
                () -> {
                    Map<String, SideEffectsSample> samples = new LinkedHashMap<>();
                    for (String id : SideEffectsService.COMPARED_SENSORS) {
                        samples.put(
                                id,
                                new SideEffectsSample(
                                        recordingNow.get(), dropped.get(), 0L, 0L, 0L, refused.get(), -1L));
                    }
                    return samples;
                },
                (ignored, id) -> new SideEffectsSample(recordingAtArm.get(), 0L, 0L, 0L, 0L, 0L, -1L));
        service.setRequestRoutes(ids -> {
            Map<String, String> named = new LinkedHashMap<>();
            for (String id : ids) {
                if (routes.containsKey(id)) {
                    named.put(id, routes.get(id));
                }
            }
            return named;
        });
        service.start();
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
