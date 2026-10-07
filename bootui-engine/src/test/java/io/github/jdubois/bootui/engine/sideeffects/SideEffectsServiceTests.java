package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sideeffectsapp.EventLoopWork;
import sideeffectsapp.Launcher;
import sideeffectsapp.ThreadWork;

/**
 * Side Effects against the real bridge class from the test class path, driven as the processes sensor's advice would
 * drive it, from an application class ({@link Launcher}), owned through the claim's handoffs.
 */
class SideEffectsServiceTests {

    private static final String REQUEST = "00000000000000ab";
    private static final String SECRET = "hunter2-secret";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean routesVisible = new AtomicBoolean(true);
    private final Map<String, String> routes = new LinkedHashMap<>();
    private final AtomicBoolean panelVisible = new AtomicBoolean(true);
    private volatile String panelVisibleExcept;
    private final AgentEvidence evidence = new AgentEvidence(
            panel -> panel.equals(BootUiPanels.HTTP_EXCHANGES)
                    ? routesVisible.get()
                    : panelVisible.get() && !panel.equals(panelVisibleExcept),
            null);
    private SideEffectsService service;
    private AgentClaim claim;
    private final Map<String, Long> buckets = new LinkedHashMap<>();

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
    void aRequestsFailedStartIsARowOfItsRouteWithTheCommandsFileNameOnly() {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("/opt/tools/report-tool", "--password", SECRET);
        Launcher.failedStart("/opt/tools/report-tool", "--password", SECRET);
        context.set(CorrelationContext.NONE);

        SideEffectsSensorReport report = service.sensor("processes", null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(report.rows()).hasSize(1);
        SideEffectsRowDto row = report.rows().get(0);
        assertThat(row.scope()).isEqualTo(SideEffectsRowDto.ROUTE);
        assertThat(row.attribution()).isEqualTo("GET /reports");
        assertThat(row.kind()).isEqualTo("process");
        assertThat(row.target()).isEqualTo("report-tool");
        assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#failedStart");
        assertThat(row.count()).isEqualTo(2L);
        assertThat(row.failed()).isEqualTo(2L);
        assertThat(row.exemplarRequestIds()).containsExactly(REQUEST);
        assertThat(report.toString())
                .doesNotContain(SECRET)
                .doesNotContain("--password")
                .doesNotContain("/opt");
        assertThat(service.report().sensors())
                .filteredOn(sensor -> sensor.id().equals("processes"))
                .singleElement()
                .satisfies(sensor -> {
                    assertThat(sensor.rows()).isEqualTo(1L);
                    assertThat(sensor.occurrences()).isEqualTo(2L);
                });
    }

    @Test
    void whileHttpExchangesIsHiddenRouteRowsAreMergedWithoutRequestIds() {
        start();
        routes.put(REQUEST, "GET /reports");
        routes.put("00000000000000cd", "POST /exports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("tool");
        context.set(CorrelationContext.forRequest("00000000000000cd"));
        Launcher.failedStart("tool");
        context.set(CorrelationContext.NONE);
        routesVisible.set(false);

        SideEffectsSensorReport report = service.sensor("processes", null, null);

        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.attribution()).isEqualTo(SideEffectsStore.ROUTE_HIDDEN);
            assertThat(row.count()).isEqualTo(2L);
            assertThat(row.exemplarRequestIds()).isEmpty();
        });
        assertThat(report.limitations()).contains(SideEffectsService.LIMITATION_ROUTES_HIDDEN);
    }

    @Test
    void aRequestTheJournalNeverNamesCountsUnderAnUnknownRouteOnceItWaitedLongEnough() {
        start();
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("tool");
        context.set(CorrelationContext.NONE);

        assertThat(service.sensor("processes", null, null).rows()).isEmpty();
        clock.addAndGet(SideEffectsStore.PENDING_MILLIS + 120_000L);

        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.attribution()).isEqualTo(SideEffectsStore.UNKNOWN_ROUTE));
    }

    @Test
    void unownedWorkIsAttributedToItsThreadFamily() throws Exception {
        start();
        Thread worker = new Thread(() -> Launcher.failedStart("tool"), "report-pool-7-thread-12");
        worker.start();
        worker.join();

        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.scope()).isEqualTo(SideEffectsRowDto.THREAD);
                    assertThat(row.attribution()).isEqualTo("report-pool-{n}-thread-{n}");
                });
    }

    @Test
    void unownedWorkBeforeTheApplicationWasReadyIsStartups() {
        clock.set(System.currentTimeMillis() + 60_000L);
        start();
        Launcher.failedStart("tool");

        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.scope()).isEqualTo(SideEffectsRowDto.STARTUP));
    }

    @Test
    void everySensorIsListedInTabOrderAndThoseNotShippedSaySo() {
        start();

        SideEffectsReport report = service.report();

        assertThat(report.sensors())
                .extracting(SideEffectsSensorDto::id)
                .containsExactly(
                        "network",
                        "files",
                        "processes",
                        "environment",
                        "thread-activity",
                        "thread-locals",
                        "resources",
                        "blocking",
                        "security-sinks");
        assertThat(report.sensors())
                .filteredOn(sensor -> !List.of(
                                "processes",
                                "network",
                                "files",
                                "environment",
                                "blocking",
                                "thread-activity",
                                "thread-locals")
                        .contains(sensor.id()))
                .allSatisfy(sensor -> {
                    assertThat(sensor.state()).isEqualTo(SideEffectsSensorDto.NOT_AVAILABLE);
                    assertThat(sensor.reason()).isEqualTo(SideEffectsCatalog.NOT_IN_THIS_VERSION);
                });
        assertThat(service.sensor("network", null, null).rows()).isEmpty();
    }

    @Test
    void anUnknownSensorOrANegativePageIsRejected() {
        start();

        assertThatThrownBy(() -> service.sensor("sockets", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sockets")
                .hasMessageContaining("processes");
        assertThatThrownBy(() -> service.sensor("processes", -1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutTheAgentEveryReadIsUnavailableWithItsReason() {
        service = new SideEffectsService(
                AgentBridgeAccess.absent(),
                () -> null,
                () -> JavaAgentService.SIDE_EFFECTS_REQUIREMENT + ": the JVM runs without the BootUI agent.",
                id -> null,
                AgentEvidence.open());

        SideEffectsReport report = service.report();
        SideEffectsSensorReport sensor = service.sensor("processes", 0, 10);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).startsWith("Requires the BootUI agent");
        assertThat(report.sensors())
                .filteredOn(dto -> dto.id().equals("processes"))
                .singleElement()
                .satisfies(dto -> assertThat(dto.state()).isEqualTo(SideEffectsSensorDto.UNAVAILABLE));
        assertThat(sensor.available()).isFalse();
        assertThat(sensor.rows()).isEmpty();
    }

    @Test
    void theAgentReportFiltersRowsAndClearRecordingDropsThem() throws Exception {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("git");
        Launcher.failedStart("convert");
        context.set(CorrelationContext.NONE);

        SideEffectsAgentReport all = service.agentReport(null, null);
        SideEffectsAgentReport git = service.agentReport("git", null);
        SideEffectsAgentReport bySensor = service.agentReport("processes", 1);

        assertThat(all.matched()).isEqualTo(2);
        assertThat(git.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo("git"));
        assertThat(bySensor.rows()).hasSize(1);
        assertThat(bySensor.omitted()).isEqualTo(1);
        assertThat(evidence.status().stores()).singleElement().satisfies(store -> {
            assertThat(store.store()).isEqualTo("side-effects");
            assertThat(store.retainedBytes()).isPositive();
            assertThat(store.counts()).containsEntry("sideEffectRows", 2L);
        });

        assertThat(evidence.clear()).isEqualTo("2 rows of Side Effects");

        assertThat(service.sensor("processes", null, null).rows()).isEmpty();
        assertThat(service.sensor("processes", null, null).limitations())
                .contains(SideEffectsService.RECORDING_CLEARED);
        // A record made after the clear is kept: the watermark is the clear's millisecond.
        Thread.sleep(5);
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.failedStart("git");
        context.set(CorrelationContext.NONE);
        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo("git"));
    }

    @Test
    void aHiddenSideEffectsPanelShowsNothingAndSaysWhy() {
        start();
        Launcher.failedStart("git");
        panelVisible.set(false);

        SideEffectsReport report = service.report();
        SideEffectsSensorReport sensor = service.sensor("processes", null, null);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("The Side Effects panel is disabled.");
        assertThat(sensor.rows()).isEmpty();
        assertThat(service.agentReport(null, null).rows()).isEmpty();
    }

    @Test
    void anUnknownSensorIdInTheSettingsIsRejectedAndOneThisVersionDoesNotShipAccepted() {
        assertThatThrownBy(() -> new AgentSensorSettings(List.of("executors", "proceses"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proceses")
                .hasMessageContaining("processes")
                .hasMessageContaining("not available in this version");
        AgentSensorSettings planned =
                new AgentSensorSettings(List.of("executors", "resources", "security-sinks"), null, null, null);
        assertThat(planned.notAvailable()).containsExactly("resources", "security-sinks");
        assertThat(planned.notAvailableWarning())
                .contains("resources, security-sinks")
                .contains("not available");
        assertThat(AgentSensorSettings.defaults().notAvailableWarning()).isNull();
        assertThat(AgentSensorSettings.NOT_AVAILABLE_SENSORS)
                .as("the catalog's sensors this version does not ship")
                .containsExactlyElementsOf(SideEffectsCatalog.SENSORS.stream()
                        .filter(sensor -> !sensor.available())
                        .map(SideEffectsCatalog.Sensor::id)
                        .toList());
        assertThat(AgentSensorSettings.KNOWN_SENSORS)
                .containsAll(SideEffectsCatalog.SENSORS.stream()
                        .filter(SideEffectsCatalog.Sensor::available)
                        .map(SideEffectsCatalog.Sensor::id)
                        .toList());
        assertThat(AgentSensorSettings.defaults().processes()).isTrue();
        assertThat(AgentSensorSettings.defaults().blocking()).isTrue();
        assertThat(AgentSensorSettings.defaults().network())
                .as("on by default (D21)")
                .isTrue();
        assertThat(AgentSensorSettings.defaults().sideEffects()).isTrue();
    }

    @Test
    void anExecutionNoRequestOwnsIsAttributedByItsJournalLabelBeforeItsThreadFamily() throws Exception {
        start();
        Map<String, String> labels = new LinkedHashMap<>();
        service.setExecutionLabels(ids -> {
            Map<String, String> named = new LinkedHashMap<>();
            for (String id : ids) {
                if (labels.containsKey(id)) {
                    named.put(id, labels.get(id));
                }
            }
            return named;
        });
        Thread job = new Thread(
                () -> {
                    context.set(CorrelationContext.forExecution("00000000000000ef"));
                    Launcher.failedStart("backup");
                    context.set(CorrelationContext.NONE);
                },
                "scheduling-1");
        job.start();
        job.join();
        assertThat(service.sensor("processes", null, null).rows())
                .as("waits for its run's event")
                .isEmpty();

        labels.put("00000000000000ef", "scheduled ReportJob.run");
        clock.addAndGet(1_000L);

        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.scope()).isEqualTo(SideEffectsRowDto.EXECUTION);
                    assertThat(row.attribution()).isEqualTo("scheduled ReportJob.run");
                    assertThat(row.exemplarRequestIds()).isEmpty();
                });
    }

    @Test
    void anExecutionTheJournalNeverNamesCountsUnderWorkNoRequestOwns() throws Exception {
        start();
        Thread job = new Thread(() -> {
            context.set(CorrelationContext.forExecution("00000000000000ef"));
            Launcher.failedStart("backup");
            context.set(CorrelationContext.NONE);
        });
        job.start();
        job.join();
        service.sensor("processes", null, null);
        clock.addAndGet(SideEffectsStore.PENDING_MILLIS + 120_000L);

        assertThat(service.sensor("processes", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.attribution()).isEqualTo(SideEffectsStore.BACKGROUND));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecordOfAnEarlierClaimGenerationIsCountedStale() throws Exception {
        start();
        service.report();
        java.lang.reflect.Field field =
                io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer.class.getDeclaredField("sideEffectsRoute");
        field.setAccessible(true);
        java.util.function.Consumer<long[]> route = (java.util.function.Consumer<long[]>) field.get(claim.drainer());
        long[] exit = new long[SideEffects.RECORD];
        exit[SideEffects.R_SENSOR] = SideEffects.SENSOR_PROCESSES;
        exit[SideEffects.R_KIND] = SideEffects.KIND_PROCESS_EXIT;
        exit[SideEffects.R_GENERATION] = claim.generation() - 1;
        exit[SideEffects.R_COUNT] = 1L;

        route.accept(exit);

        assertThat(service.status()).containsEntry("staleRecords", 1L).containsEntry("records", 0L);
        assertThat(service.sensor("processes", null, null).rows()).isEmpty();
    }

    @Test
    void clearRecordingResetsTheOtherRowsCount() {
        start();
        Launcher.failedStart("git");

        evidence.clear();

        assertThat(service.status()).containsEntry("folded", 0L).containsEntry("rows", 0);
    }

    @Test
    void aSleepAndAWaitOnAnEventLoopAreRowsOfTheirRouteByLoopFamilyOperationAndCallSite() throws Exception {
        start();
        SideEffects.enable(SideEffects.MASK_BLOCKING);
        routes.put(REQUEST, "GET /slow");
        Object monitor = new Object();
        onThread("reactor-http-nio-3", () -> {
            context.set(CorrelationContext.forRequest(REQUEST));
            EventLoopWork.register();
            EventLoopWork.sleep(5L);
            EventLoopWork.sleep(5L);
            EventLoopWork.waitOn(monitor, 5L);
            context.set(CorrelationContext.NONE);
        });
        // The same sleep off event loops is never a row.
        onThread("boundedElastic-1", () -> EventLoopWork.sleep(5L));

        SideEffectsSensorReport report = service.sensor("blocking", null, null);

        assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(report.rows()).hasSize(2);
        SideEffectsRowDto sleep = report.rows().get(0);
        assertThat(sleep.scope()).isEqualTo(SideEffectsRowDto.ROUTE);
        assertThat(sleep.attribution()).isEqualTo("GET /slow");
        assertThat(sleep.sensor()).isEqualTo("blocking");
        assertThat(sleep.kind()).isEqualTo("sleep");
        assertThat(sleep.target()).isEqualTo("reactor-http-nio-{n}");
        assertThat(sleep.callSite()).isEqualTo("sideeffectsapp.EventLoopWork#sleep");
        assertThat(sleep.count()).isEqualTo(2L);
        assertThat(sleep.completed()).isZero();
        assertThat(sleep.totalMillis()).isGreaterThanOrEqualTo(10L);
        assertThat(sleep.maxMillis()).isGreaterThanOrEqualTo(5L);
        assertThat(sleep.exemplarRequestIds()).containsExactly(REQUEST);
        SideEffectsRowDto wait = report.rows().get(1);
        assertThat(wait.kind()).as("a wait, never a process exit").isEqualTo("wait");
        assertThat(wait.count()).isEqualTo(1L);
        assertThat(report.limitations()).contains(SideEffectsService.LIMITATION_BLOCKING);
        assertThat(service.agentReport("blocking", null).rows()).hasSize(2);
    }

    @Test
    void blockingIsNotApplicableOnAStackWithoutEventLoopsUntilALoopIsRegistered() throws Exception {
        start();
        SideEffects.enable(SideEffects.MASK_BLOCKING);
        service.setServerEventLoops(false);

        assertThat(blockingSensor().state()).isEqualTo(SideEffectsSensorDto.NOT_APPLICABLE);
        assertThat(blockingSensor().reason()).isEqualTo(SideEffectsService.BLOCKING_NOT_APPLICABLE);
        assertThat(service.report().limitations()).doesNotContain(SideEffectsService.LIMITATION_NO_EVENT_LOOP);

        // A WebClient's event loop, once registered, is watched.
        Thread loop = new Thread(EventLoopWork::register, "reactor-http-nio-1");
        loop.start();
        loop.join();
        assertThat(blockingSensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        keep = loop;
    }

    @Test
    void aStackWithEventLoopsSaysWhenNoneHandledARequestYet() {
        start();
        SideEffects.enable(SideEffects.MASK_BLOCKING);

        assertThat(blockingSensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(service.report().limitations()).contains(SideEffectsService.LIMITATION_NO_EVENT_LOOP);
        assertThat(service.report().limitations()).doesNotContain(SideEffectsService.LIMITATION_LOOPS_REFUSED);
    }

    @Test
    void saysWhenTheEventLoopTableRefusedARegistration() throws Exception {
        start();
        SideEffects.enable(SideEffects.MASK_BLOCKING);
        java.lang.reflect.Field full =
                Class.forName("io.github.jdubois.bootui.agent.bridge.Blocking").getDeclaredField("FULL");
        full.setAccessible(true);
        ((java.util.concurrent.atomic.LongAdder) full.get(null)).increment();

        assertThat(service.report().limitations()).contains(SideEffectsService.LIMITATION_LOOPS_REFUSED);
    }

    @Test
    void saysWhenThreadActivityCouldNotCheckThreadsAtTheirRequestsEnd() throws Exception {
        start();
        assertThat(service.report().limitations()).noneMatch(line -> line.contains("not checked"));
        java.lang.reflect.Field field = Class.forName("io.github.jdubois.bootui.agent.bridge.ThreadActivity")
                .getDeclaredField("TRACKER");
        field.setAccessible(true);
        Object tracker = field.get(null);
        for (String counter : List.of("unresolved", "endsLost")) {
            java.lang.reflect.Field adder = tracker.getClass().getDeclaredField(counter);
            adder.setAccessible(true);
            ((java.util.concurrent.atomic.LongAdder) adder.get(tracker)).add(2);
        }
        try {
            assertThat(service.report().limitations())
                    .anyMatch(line -> line.startsWith("2 threads or executors were not checked"))
                    .anyMatch(line -> line.startsWith("2 requests' ends were lost"));
        } finally {
            for (String counter : List.of("unresolved", "endsLost")) {
                java.lang.reflect.Field adder = tracker.getClass().getDeclaredField(counter);
                adder.setAccessible(true);
                ((java.util.concurrent.atomic.LongAdder) adder.get(tracker)).reset();
            }
        }
    }

    /** Holds a registered loop's thread, so its weak entry outlives the test's assertions. */
    private Thread keep;

    private SideEffectsSensorDto blockingSensor() {
        return service.report().sensors().stream()
                .filter(sensor -> sensor.id().equals("blocking"))
                .findFirst()
                .orElseThrow();
    }

    interface Work {
        void run() throws Exception;
    }

    private static void onThread(String name, Work work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(
                () -> {
                    try {
                        work.run();
                        SideEffects.flushThread();
                    } catch (Throwable ex) {
                        failure.set(ex);
                    }
                },
                name);
        thread.start();
        thread.join(10_000L);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void aReportWrittenOutsideTheTemporaryDirectoryIsAnApplicationWriteOfItsRouteByPatternOnly() {
        start();
        routes.put(REQUEST, "GET /reports");
        context.set(CorrelationContext.forRequest(REQUEST));
        Launcher.writeFile("/srv/exports/" + SECRET + "-2026-10-05.csv");
        Launcher.writeFile("/srv/exports/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlMTIz/x.csv");
        context.set(CorrelationContext.NONE);

        SideEffectsSensorReport report = service.sensor("files", null, null);

        assertThat(report.rows())
                .filteredOn(row -> row.target().endsWith(".csv") && row.target().contains("-{n}-{n}-{n}"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.scope()).isEqualTo(SideEffectsRowDto.ROUTE);
                    assertThat(row.attribution()).isEqualTo("GET /reports");
                    assertThat(row.kind()).isEqualTo("write");
                    assertThat(row.target()).isEqualTo("/srv/exports/hunter{n}-secret-{n}-{n}-{n}.csv");
                    assertThat(row.origin()).isEqualTo(SideEffectOrigins.APPLICATION);
                    assertThat(row.location()).isEqualTo(SideEffectOrigins.ELSEWHERE);
                    assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#writeFile");
                    assertThat(row.exemplarRequestIds()).containsExactly(REQUEST);
                });
        assertThat(report.toString()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(service.modelAccesses()).anySatisfy(access -> {
            assertThat(access.fromKey()).isEqualTo("GET /reports");
            assertThat(access.type()).isEqualTo(io.github.jdubois.bootui.engine.model.EdgeType.OPENS);
            assertThat(access.toKey()).isEqualTo("/srv/exports/hunter{n}-secret-{n}-{n}-{n}.csv");
        });
    }

    @Test
    void aPropertyReadDuringARequestIsItsRouteByNameNeverItsValue() {
        start();
        routes.put(REQUEST, "GET /reports");
        System.setProperty("sample.report.title", SECRET);
        try {
            context.set(CorrelationContext.forRequest(REQUEST));
            Launcher.readProperty("sample.report.title");
            context.set(CorrelationContext.NONE);
        } finally {
            System.clearProperty("sample.report.title");
        }

        SideEffectsSensorReport report = service.sensor("environment", null, null);

        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.attribution()).isEqualTo("GET /reports");
            assertThat(row.kind()).isEqualTo("system property");
            assertThat(row.target()).isEqualTo("sample.report.title");
            assertThat(row.origin()).isEqualTo(SideEffectOrigins.APPLICATION);
            assertThat(row.location()).isNull();
        });
        assertThat(report.toString()).doesNotContain(SECRET);
        assertThat(service.modelAccesses())
                .anySatisfy(access -> assertThat(access.toKey()).isEqualTo("property:sample.report.title"));
    }

    @Test
    void bucketsAreRowsGroupedApartCountedSinceTheLastClear() {
        start();
        buckets.put("classFiles", 40L);
        buckets.put("javaHome", 2L);

        assertThat(service.sensor("files", null, null).rows())
                .extracting(SideEffectsRowDto::target, SideEffectsRowDto::count, SideEffectsRowDto::origin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("(class files)", 40L, SideEffectOrigins.CLASS_PATH),
                        org.assertj.core.groups.Tuple.tuple("(files in Java's home)", 2L, SideEffectOrigins.JDK));

        evidence.clear();
        buckets.put("classFiles", 45L);

        assertThat(service.sensor("files", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.count()).isEqualTo(5L));
        assertThat(service.modelAccesses()).isEmpty();
    }

    @Test
    void anSdksConnectNoRestClientCallNamesIsARowOfItsRouteNotCapturedByAnyPanel() {
        start();
        SideEffects.enable(SideEffects.MASK_NETWORK);
        routes.put(REQUEST, "GET /sdk");
        context.set(CorrelationContext.forRequest(REQUEST));
        sideeffectsapp.Dialer.connect("localhost", 9000);
        sideeffectsapp.Dialer.lookup("db.internal");
        context.set(CorrelationContext.NONE);
        clock.set(System.currentTimeMillis() + 1_000L);
        // The first read names the request; its grace runs from then.
        service.sensor("network", null, null);
        clock.addAndGet(SideEffectsStore.CAPTURE_GRACE_MILLIS);

        SideEffectsSensorReport report = service.sensor("network", null, null);

        assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .filteredOn(row -> row.kind().equals("connect"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.attribution()).isEqualTo("GET /sdk");
                    assertThat(row.target()).isEqualTo("localhost:9000");
                    assertThat(row.callSite()).isEqualTo("sideeffectsapp.Dialer#connect");
                    assertThat(row.client()).isNull();
                    assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED);
                    assertThat(row.capturedBy()).isNull();
                    assertThat(row.completed()).isEqualTo(1L);
                    assertThat(row.exemplarRequestIds()).containsExactly(REQUEST);
                });
        assertThat(report.rows())
                .filteredOn(row -> row.kind().equals("lookup"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.target()).isEqualTo("db.internal");
                    assertThat(row.capture()).isNull();
                });
        assertThat(service.agentReport("not captured", null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo("localhost:9000"));
        assertThat(service.hostOpens())
                .extracting(io.github.jdubois.bootui.engine.model.HostOpen::from)
                .containsExactlyInAnyOrder(
                        io.github.jdubois.bootui.engine.model.HostOpen.ROUTE,
                        io.github.jdubois.bootui.engine.model.HostOpen.CLASS);
        assertThat(report.limitations()).contains(SideEffectsService.LIMITATION_CAPTURE);
    }

    @Test
    void aConnectARestClientCallOfTheSameRequestNamesIsCapturedByRestClientTraceWhileItIsVisible() {
        start();
        SideEffects.enable(SideEffects.MASK_NETWORK);
        JournalNetworkCapture capture = new JournalNetworkCapture(null, key -> null);
        capture.learn(new io.github.jdubois.bootui.engine.journal.RuntimeEvent(
                io.github.jdubois.bootui.engine.journal.JournalSource.REST_CLIENT,
                System.currentTimeMillis(),
                1_000_000L,
                REQUEST,
                null,
                null,
                "main",
                null,
                false,
                new io.github.jdubois.bootui.engine.journal.RestClientPayload(
                        "GET", "localhost:9000", "/x", 200, "RestClient", false)));
        service.setNetworkCapture(capture);
        routes.put(REQUEST, "GET /rest");
        context.set(CorrelationContext.forRequest(REQUEST));
        sideeffectsapp.Dialer.connect("localhost", 9000);
        context.set(CorrelationContext.NONE);
        clock.set(System.currentTimeMillis() + 500L);

        assertThat(service.sensor("network", null, null).rows()).singleElement().satisfies(row -> {
            assertThat(row.capture()).isEqualTo(SideEffectsRowDto.CAPTURED);
            assertThat(row.capturedBy()).isEqualTo(BootUiPanels.REST_CLIENT_TRACE);
        });

        panelVisibleExcept = BootUiPanels.REST_CLIENT_TRACE;
        assertThat(service.sensor("network", null, null).rows())
                .as("no visible panel shows it")
                .singleElement()
                .satisfies(row -> assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED));
    }

    /**
     * M5-5e: a thread and an executor a request's application code left running when it ended are rows of its route,
     * reported left running once the request's end, heard from {@code RequestPhases}, is past its grace; a thread joined
     * before the end is a row never left running, and an executor's shutdown lands on its creation's row.
     */
    @Test
    void threadsAndExecutorsARequestLeftRunningAreRowsOfItsRoute() throws Exception {
        start();
        RequestPhases phases = new RequestPhases();
        service.listenToRequestEnds(phases);
        routes.put(REQUEST, "GET /refresh");
        CountDownLatch release = new CountDownLatch(1);
        context.set(CorrelationContext.forRequest(REQUEST));
        Thread left = ThreadWork.startWaiting("report-refresher-1", release);
        ThreadWork.startAndJoin("report-refresh-now-2");
        java.util.concurrent.ThreadPoolExecutor executor = ThreadWork.create();
        context.set(CorrelationContext.NONE);
        phases.end(REQUEST);
        try {
            Thread.sleep(400);
            SideEffectsSensorReport report = service.sensor("thread-activity", null, null);

            assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
            assertThat(report.rows())
                    .filteredOn(row -> row.target().equals("report-refresher-{n}"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.scope()).isEqualTo(SideEffectsRowDto.ROUTE);
                        assertThat(row.attribution()).isEqualTo("GET /refresh");
                        assertThat(row.kind()).isEqualTo("thread");
                        assertThat(row.origin()).isEqualTo("application");
                        assertThat(row.callSite()).isEqualTo("sideeffectsapp.ThreadWork#start");
                        assertThat(row.count()).isEqualTo(1L);
                        assertThat(row.requests()).isEqualTo(1L);
                        assertThat(row.leftRunning()).isEqualTo(1L);
                        assertThat(row.exemplarRequestIds()).containsExactly(REQUEST);
                    });
            assertThat(report.rows())
                    .filteredOn(row -> row.target().equals("report-refresh-now-{n}"))
                    .singleElement()
                    .satisfies(row -> assertThat(row.leftRunning()).as("joined").isZero());
            SideEffectsRowDto created = report.rows().stream()
                    .filter(row -> row.kind().equals("executor"))
                    .findFirst()
                    .orElseThrow();
            assertThat(created.target()).isEqualTo("java.util.concurrent.ThreadPoolExecutor");
            assertThat(created.leftRunning()).isEqualTo(1L);
            assertThat(created.completed()).isZero();

            ThreadWork.shutdown(executor);

            assertThat(service.sensor("thread-activity", null, null).rows())
                    .filteredOn(row -> row.kind().equals("executor"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.count())
                                .as("a shutdown is never a creation")
                                .isEqualTo(1L);
                        assertThat(row.completed()).isEqualTo(1L);
                        assertThat(row.leftRunning()).isEqualTo(1L);
                    });
            assertThat(report.limitations()).contains(SideEffectsService.LIMITATION_THREADS);
        } finally {
            release.countDown();
            left.join();
        }
    }

    private void start() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("sideeffectsapp"),
                new AgentSensorSettings(
                        List.of("processes", "network", "files", "environment", "blocking", "thread-activity"),
                        List.of(),
                        List.of(),
                        null,
                        AgentSensorSettings.DEFAULT_RING_CAPACITY));
        claim.attach(new AgentHandoffs(context::get, null, null));
        SideEffects.enable(SideEffects.MASK_PROCESSES
                | SideEffects.MASK_FILES
                | SideEffects.MASK_ENVIRONMENT
                | SideEffects.MASK_THREADS);
        service = new SideEffectsService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(
                        SideEffectsSensorDto.RECORDING, null, List.of(), 0L, buckets),
                evidence,
                clock::get,
                "/home/someone");
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
