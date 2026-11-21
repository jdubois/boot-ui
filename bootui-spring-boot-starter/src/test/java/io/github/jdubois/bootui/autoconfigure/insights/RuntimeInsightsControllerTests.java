package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.CacheCapture;
import io.github.jdubois.bootui.engine.insights.EventLoopBlocking;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.ProxyBypass;
import io.github.jdubois.bootui.engine.insights.RepeatedSelects;
import io.github.jdubois.bootui.engine.insights.RouteTimeBreakdown;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.DurableJournalCapture;
import io.github.jdubois.bootui.engine.journal.DurableJournalCaptureSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalIdentity;
import io.github.jdubois.bootui.engine.journal.DurableJournalPage;
import io.github.jdubois.bootui.engine.journal.DurableJournalRun;
import io.github.jdubois.bootui.engine.journal.DurableJournalRunMetadata;
import io.github.jdubois.bootui.engine.journal.DurableJournalRuntime;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStore;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JdbcDurableJournalStore;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEvidenceSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

class RuntimeInsightsControllerTests {

    private final AtomicLong cacheTime = new AtomicLong(1_000);
    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    private JournalAggregates recordingAggregates() {
        JournalAggregates aggregates = new JournalAggregates();
        journal.addListener(aggregates);
        return aggregates;
    }

    @Test
    void servesTheReportAndAnUnknownObservationAsUnavailableWithoutRecordingAnything() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(JournalAggregates.class, this::recordingAggregates);
            context.registerBean(BootUiProperties.class, BootUiProperties::new);
            context.registerBean(RuntimeInsightsController.class);
            context.refresh();
            MockMvc mvc = standaloneSetup(context.getBean(RuntimeInsightsController.class))
                    .build();

            mvc.perform(get("/bootui/api/runtime-insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.window.requests").value(0))
                    .andExpect(jsonPath("$.checks.length()").value(29));
            mvc.perform(get("/bootui/api/runtime-insights/insights/repeated-selects:0000000000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
            mvc.perform(get("/bootui/api/runtime-insights/impact").param("symbol", "orderService"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.reason")
                            .value(org.hamcrest.Matchers.containsString("beans could not be read")));
            mvc.perform(get("/bootui/api/runtime-insights/impact/symbols").param("query", "orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.query").value("orders"))
                    .andExpect(jsonPath("$.symbols.length()").value(0))
                    .andExpect(jsonPath("$.total").value(0));
            assertThat(journal.status().lastSequence())
                    .as("reading the panel records nothing")
                    .isLessThanOrEqualTo(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void realCacheProxiesDoNotBecomeBypassesWhenCaptureIsDisabled(boolean reactive, boolean capture) throws Exception {
        try (GenericApplicationContext context =
                reactive ? new GenericReactiveWebApplicationContext() : new GenericApplicationContext()) {
            CacheActivityRecorder recorder = new CacheActivityRecorder(capture, 100, cacheTime::get);
            AtomicReference<CorrelationContext> active = new AtomicReference<>(CorrelationContext.NONE);
            AtomicInteger calls = new AtomicInteger();
            recorder.setRuntimeEventSink(journal);
            recorder.setCorrelationContextProvider(active::get);
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(SqlTraceRecorder.class, RuntimeInsightsControllerTests::tracedRecorder);
            context.registerBean(CacheActivityRecorder.class, () -> recorder);
            context.registerBean(
                    CacheActivityCacheManagerBeanPostProcessor.class,
                    () -> new CacheActivityCacheManagerBeanPostProcessor(
                            context.getBeanProvider(CacheActivityRecorder.class),
                            context.getBeanProvider(BootUiSelfDataFilter.class)));
            context.registerBean(
                    "cacheManager", ConcurrentMapCacheManager.class, () -> new ConcurrentMapCacheManager("prices"));
            context.registerBean(CachedPrices.class, () -> new CachedPrices(journal, active, calls, cacheTime::get));
            new AnnotatedBeanDefinitionReader(context).register(CacheProxyConfiguration.class);
            context.refresh();
            CachedPrices prices = context.getBean(CachedPrices.class);
            assertThat(AopUtils.isAopProxy(prices)).isTrue();
            assertThat(recorder.hasInstrumentedManager()).isEqualTo(capture);

            capturedCacheRequest(
                    active, "proxied-miss", () -> assertThat(prices.price()).isEqualTo(1));
            capturedCacheRequest(
                    active, "proxied-hit", () -> assertThat(prices.price()).isEqualTo(1));
            assertThat(calls.get())
                    .as("Spring caching still works even without BootUI capture")
                    .isEqualTo(1);
            RuntimeInsightsController controller = controller(context);
            RuntimeInsightsReportDto proxied = controller.report();
            assertThat(proxied.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            if (!capture) {
                assertThat(proxied.checks())
                        .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                        .singleElement()
                        .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.DISABLED));
            }

            capturedCacheRequest(active, "self-invocation", prices::bypassed);
            long watermark = journal.status().lastSequence();
            RuntimeInsightsReportDto bypassed = controller.report();
            if (capture) {
                assertThat(bypassed.observations())
                        .filteredOn(row -> row.kind().equals(ProxyBypass.KIND))
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.sentence()).contains("@Cacheable(prices)");
                            assertThat(row.affected()).isEqualTo(1);
                            assertThat(row.exemplarRequestIds()).containsExactly("self-invocation");
                        });
            } else {
                assertThat(bypassed.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            }
            assertThat(calls.get())
                    .as("render never invokes an application method")
                    .isEqualTo(2);
            assertThat(journal.status().lastSequence()).isEqualTo(watermark);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateInstrumentationAndResumeNeverReclassifyEarlierRequests(boolean reactive) throws Exception {
        try (GenericApplicationContext context =
                reactive ? new GenericReactiveWebApplicationContext() : new GenericApplicationContext()) {
            AtomicReference<CorrelationContext> active = new AtomicReference<>(CorrelationContext.NONE);
            AtomicInteger calls = new AtomicInteger();
            CachedPrices raw = new CachedPrices(journal, active, calls, cacheTime::get);
            capturedCacheRequest(active, "uninstrumented", raw::price);
            cacheTime.set(2_000);
            CacheActivityRecorder recorder = new CacheActivityRecorder(true, 100, cacheTime::get);
            recorder.setRuntimeEventSink(journal);
            recorder.setCorrelationContextProvider(active::get);
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(SqlTraceRecorder.class, RuntimeInsightsControllerTests::tracedRecorder);
            context.registerBean(CacheActivityRecorder.class, () -> recorder);
            context.registerBean(
                    CacheActivityCacheManagerBeanPostProcessor.class,
                    () -> new CacheActivityCacheManagerBeanPostProcessor(
                            context.getBeanProvider(CacheActivityRecorder.class),
                            context.getBeanProvider(BootUiSelfDataFilter.class)));
            context.registerBean(
                    "cacheManager", ConcurrentMapCacheManager.class, () -> new ConcurrentMapCacheManager("prices"));
            context.registerBean(CachedPrices.class, () -> new CachedPrices(journal, active, calls, cacheTime::get));
            new AnnotatedBeanDefinitionReader(context).register(CacheProxyConfiguration.class);
            context.refresh();
            RuntimeInsightsController controller = controller(context);
            RuntimeInsightsReportDto missing = controller.report();
            assertThat(missing.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(missing.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.BEFORE_WINDOW));
            CachedPrices prices = context.getBean(CachedPrices.class);
            capturedCacheRequest(active, "supported-bypass", prices::bypassed);
            var supported = controller.report().observations().stream()
                    .filter(row -> row.kind().equals(ProxyBypass.KIND))
                    .findFirst()
                    .orElseThrow();
            assertThat(supported.exemplarRequestIds()).containsExactly("supported-bypass");
            cacheTime.set(3_000);
            recorder.suspendForLowFootprint();
            assertThat(controller.report().observations())
                    .noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(controller.report().checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.PAUSED));
            assertThat(controller.insight(supported.id()).available()).isFalse();
            capturedCacheRequest(active, "paused", prices::bypassed);
            cacheTime.set(4_000);
            recorder.resumeFromLowFootprint();
            assertThat(controller.report().observations())
                    .noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            capturedCacheRequest(active, "after-resume", prices::bypassed);
            long watermark = journal.status().lastSequence();
            assertThat(controller.report().observations())
                    .filteredOn(row -> row.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(row -> assertThat(row.exemplarRequestIds()).containsExactly("after-resume"));
            assertThat(calls.get()).isEqualTo(4);
            assertThat(journal.status().lastSequence()).isEqualTo(watermark);
        }
    }

    private void capturedCacheRequest(AtomicReference<CorrelationContext> active, String requestId, Runnable work)
            throws InterruptedException {
        long start = cacheTime.incrementAndGet();
        active.set(CorrelationContext.forRequest(requestId));
        try {
            work.run();
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    start,
                    5_000_000,
                    active.get(),
                    Thread.currentThread().getName(),
                    null,
                    false,
                    new HttpPayload("GET", "/prices", "/prices", null, 200)));
        } finally {
            active.set(CorrelationContext.NONE);
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class CacheProxyConfiguration {}

    static class CachedPrices {
        private final RuntimeJournal journal;
        private final AtomicReference<CorrelationContext> active;
        private final AtomicInteger calls;
        private final LongSupplier epochMillis;

        CachedPrices(
                RuntimeJournal journal,
                AtomicReference<CorrelationContext> active,
                AtomicInteger calls,
                LongSupplier epochMillis) {
            this.journal = journal;
            this.active = active;
            this.calls = calls;
            this.epochMillis = epochMillis;
        }

        @Cacheable("prices")
        public int price() {
            calls.incrementAndGet();
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    epochMillis.getAsLong(),
                    100,
                    active.get(),
                    Thread.currentThread().getName(),
                    null,
                    false,
                    new SqlPayload(
                            "select * from prices",
                            null,
                            "db",
                            false,
                            ApplicationFrames.of(List.of(
                                    CachedPrices.class.getName() + ".price(RuntimeInsightsControllerTests.java:1)")),
                            null,
                            System.nanoTime())));
            return 1;
        }

        public int bypassed() {
            return price();
        }
    }

    @Test
    void aHotUnselectedReportKeepsTheNoArgPathWithoutReadingDurableStorage() {
        DurableJournalStore store = mock(DurableJournalStore.class);
        DurableJournalRuntime runtime = new DurableJournalRuntime();
        assertThat(runtime.activate(store, mock(DurableJournalCapture.class))).isTrue();
        DurableJournalSettings settings =
                durableSettings(journal.run().id(), journal.status().instanceId(), false);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(DurableJournalSettings.class, () -> settings);
            context.registerBean(DurableJournalRuntime.class, () -> runtime);
            context.refresh();

            RuntimeInsightsController controller = controller(context);

            assertThat(controller.report().available()).isTrue();
            verifyNoInteractions(store);
        }
    }

    @Test
    void currentReportRecoversPersistedRequestsAfterMemoryOffloadBeforeAnyCompaction() throws Exception {
        DurableJournalSettings settings =
                durableSettings(journal.run().id(), journal.status().instanceId(), false);
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:insights-offload;DB_CLOSE_DELAY=-1");
        JdbcDurableJournalStore store = spy(new JdbcDurableJournalStore(dataSource, "journal"));
        store.verifySchema();
        CountDownLatch checkpointed = new CountDownLatch(1);
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    checkpointed.countDown();
                    return result;
                })
                .when(store)
                .checkpoint(any(), anyLong());
        DurableJournalRuntime runtime = new DurableJournalRuntime();
        try (DurableJournalCapture capture = DurableJournalCapture.start(
                        journal,
                        settings,
                        store,
                        new JournalActivityFeed(0, 2, null),
                        DurableJournalCaptureSettings.of(settings),
                        List.of(),
                        phase -> DurableJournalRunMetadata.snapshot(
                                phase, journal, settings.identity(), InsightsStack.SPRING_MVC, Map.of(), null, null));
                GenericApplicationContext context = new GenericApplicationContext()) {
            assertThat(runtime.activate(store, capture)).isTrue();
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(DurableJournalSettings.class, () -> settings);
            context.registerBean(DurableJournalRuntime.class, () -> runtime);
            context.refresh();
            RuntimeInsightsController controller = controller(context);

            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    1_000_000,
                    CorrelationContext.forRequest("persisted"),
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/persisted", "/persisted", null, 200)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            capture.flushNow();
            assertThat(checkpointed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.findRunMetadata(settings.scope())).isNotNull();

            assertThat(journal.offloadRetainedData()).isEqualTo(1);
            assertThat(journal.status().offloadedEvents()).isZero();
            assertThat(store.query(settings.scope()).entries()).hasSize(1);
            assertThat(controller.report().window().requests()).isEqualTo(1);

            journal.clear();
            assertThat(controller.report().window().requests()).isZero();
        }
    }

    @Test
    void selectedHistoricalReportUsesDurableEvidenceAndRejectsUnknownScope() throws Exception {
        RuntimeJournal old = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        try {
            old.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    1_000_000,
                    CorrelationContext.forRequest("historical"),
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/historical", "/historical", null, 200)));
            assertThat(old.awaitDrained(Duration.ofSeconds(5))).isTrue();
            JournalEvidenceSnapshot snapshot = JournalEvidenceSnapshot.hot(old);
            DurableJournalSettings settings =
                    durableSettings(journal.run().id(), journal.status().instanceId(), false);
            DurableJournalScope scope = new DurableJournalScope(
                    settings.namespaceId(), settings.applicationId(), old.run().id());
            DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                    DurableJournalRunMetadata.Phase.CHECKPOINT,
                    old,
                    new DurableJournalIdentity(
                            scope.namespace(),
                            scope.application(),
                            scope.run(),
                            "process",
                            snapshot.status().instanceId()),
                    io.github.jdubois.bootui.engine.insights.InsightsStack.SPRING_MVC,
                    Map.of(),
                    null,
                    null);
            DurableJournalStore store = mock(DurableJournalStore.class);
            when(store.findRun(scope))
                    .thenReturn(new DurableJournalRun(
                            scope,
                            1,
                            1,
                            "RUNNING",
                            null,
                            old.lastSequence(),
                            snapshot.status().instanceId(),
                            metadata));
            when(store.findRunMetadata(scope)).thenReturn(metadata);
            when(store.query(any())).thenReturn(new DurableJournalPage(snapshot.entries(), null, false));
            when(store.listDetails(any(), anyLong(), anyInt())).thenReturn(List.of());
            DurableJournalRuntime runtime = new DurableJournalRuntime();
            assertThat(runtime.activate(store, mock(DurableJournalCapture.class)))
                    .isTrue();
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                context.registerBean(RuntimeJournal.class, () -> journal);
                context.registerBean(DurableJournalSettings.class, () -> settings);
                context.registerBean(DurableJournalRuntime.class, () -> runtime);
                context.refresh();
                RuntimeInsightsController controller = controller(context);

                assertThat(controller.report(scope.namespace(), scope.application(), scope.run()))
                        .satisfies(report -> {
                            assertThat(report.available()).isTrue();
                            assertThat(report.window().runId())
                                    .isEqualTo(old.run().id());
                            assertThat(report.window().requests()).isEqualTo(1);
                        });
                assertThat(controller
                                .report(scope.namespace(), scope.application(), "missing-run")
                                .available())
                        .isFalse();
                assertThatThrownBy(() -> controller.report("other-namespace", scope.application(), scope.run()))
                        .isInstanceOf(ResponseStatusException.class)
                        .satisfies(ex -> assertThat(((ResponseStatusException) ex)
                                        .getStatusCode()
                                        .value())
                                .isEqualTo(400));
            }
        } finally {
            old.close();
        }
    }

    private static DurableJournalSettings durableSettings(
            String runId, String instanceId, boolean readerArchiveCrossNamespace) {
        return new DurableJournalSettings(
                new DurableJournalIdentity("local", "orders", runId, "process", instanceId),
                true,
                Duration.ofSeconds(1),
                100,
                1024 * 1024,
                100,
                "bootui_activity",
                Duration.ofDays(7),
                readerArchiveCrossNamespace);
    }

    @Test
    void comparisonReadsLiveSourcePanelPolicyOnBothSpringStacks() throws Exception {
        JournalAggregates captured = recordingAggregates();
        RuntimeEvent request = RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                1_000_000,
                CorrelationContext.forRequest("captured"),
                "worker",
                null,
                false,
                new HttpPayload("GET", "/private", "/private", null, 200));
        assertThat(journal.offer(request)).isTrue();
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        try (GenericApplicationContext servlet = new GenericApplicationContext();
                GenericReactiveWebApplicationContext reactive = new GenericReactiveWebApplicationContext()) {
            for (GenericApplicationContext context : List.of(servlet, reactive)) {
                context.registerBean(RuntimeJournal.class, () -> journal);
                context.registerBean(JournalAggregates.class, () -> captured);
                context.refresh();
                BootUiProperties properties = new BootUiProperties();
                RuntimeInsightsController controller = new RuntimeInsightsController(
                        context,
                        properties,
                        context.getBeanProvider(RuntimeJournal.class),
                        context.getBeanProvider(JournalAggregates.class),
                        context.getBeanProvider(MappingProvider.class),
                        context.getBeanProvider(BeanProvider.class));
                assertThat(controller.comparison(null).current().requests()).isEqualTo(1);
                properties.panel(BootUiPanels.HTTP_EXCHANGES).setEnabled(false);
                assertThat(controller.comparison(null).current().requests()).isZero();
                assertThat(controller.comparison(null).limitations())
                        .contains("Facts are not compared because http-exchanges is disabled.");
                properties.panel(BootUiPanels.HTTP_EXCHANGES).setEnabled(true);
                assertThat(controller.comparison(null).current().requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void listsTheDeclaredRoutesNoRequestOfThisRunReached() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(JournalAggregates.class, this::recordingAggregates);
            context.registerBean(BootUiProperties.class, BootUiProperties::new);
            context.registerBean(MappingProvider.class, () -> new MappingProvider() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public List<MappingDto> mappings() {
                    return List.of(new MappingDto("GET", "/api/orders", "com.example.Orders#list()", null, null));
                }
            });
            context.registerBean(RuntimeInsightsController.class);
            context.refresh();
            MockMvc mvc = standaloneSetup(context.getBean(RuntimeInsightsController.class))
                    .build();

            mvc.perform(get("/bootui/api/runtime-insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notExercised[0]").value("GET /api/orders"))
                    .andExpect(jsonPath("$.notExercisedOmitted").value(0));
        }
    }

    @Test
    void theStackDecidesWhereAnObservationApplies() {
        try (GenericApplicationContext servlet = new GenericApplicationContext();
                GenericReactiveWebApplicationContext reactive = new GenericReactiveWebApplicationContext()) {
            servlet.registerBean(RuntimeJournal.class, () -> journal);
            reactive.registerBean(RuntimeJournal.class, () -> journal);
            reactive.registerBean(SqlTraceRecorder.class, RuntimeInsightsControllerTests::tracedRecorder);
            servlet.refresh();
            reactive.refresh();

            assertThat(eventLoopCheck(servlet).status()).isEqualTo("NOT_APPLICABLE");
            assertThat(eventLoopCheck(reactive)).satisfies(check -> {
                assertThat(check.status()).isEqualTo("INSUFFICIENT");
                assertThat(check.eligibleRequests()).isZero();
                assertThat(check.reason()).contains("No eligible work");
            });
        }
    }

    @Test
    void sqlChecksAreUnavailableOnSpringMvcAndWebFluxWhenNoDataSourceIsTraced() {
        try (GenericApplicationContext servlet = new GenericApplicationContext();
                GenericReactiveWebApplicationContext reactive = new GenericReactiveWebApplicationContext();
                GenericApplicationContext traced = new GenericApplicationContext();
                GenericApplicationContext disabled = new GenericApplicationContext()) {
            for (GenericApplicationContext context : List.of(servlet, reactive, traced, disabled)) {
                context.registerBean(RuntimeJournal.class, () -> journal);
            }
            // A WebFlux application reading through R2DBC: SQL Trace is on, but no DataSource bean was ever traced.
            reactive.registerBean(
                    SqlTraceRecorder.class, () -> new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5));
            traced.registerBean(SqlTraceRecorder.class, RuntimeInsightsControllerTests::tracedRecorder);
            disabled.registerBean(
                    SqlTraceRecorder.class,
                    () -> new SqlTraceRecorder(false, true, false, false, 8, 100, 2000, 200, 5));
            servlet.refresh();
            reactive.refresh();
            traced.refresh();
            disabled.refresh();

            assertThat(check(servlet, RepeatedSelects.KIND))
                    .satisfies(check -> assertThat(check.status()).isEqualTo("UNAVAILABLE"))
                    .satisfies(check -> assertThat(check.reason()).isEqualTo(SqlCapture.NOT_RECORDED));
            assertThat(check(reactive, RepeatedSelects.KIND))
                    .satisfies(check -> assertThat(check.status()).isEqualTo("UNAVAILABLE"))
                    .satisfies(check -> assertThat(check.reason()).isEqualTo(SqlCapture.NOT_RECORDED));
            assertThat(check(reactive, EventLoopBlocking.KIND)).satisfies(check -> {
                assertThat(check.status()).isEqualTo("INSUFFICIENT");
                assertThat(check.eligibleRequests()).isZero();
                assertThat(check.reason()).contains(SqlCapture.NOT_RECORDED);
            });
            assertThat(check(disabled, RepeatedSelects.KIND).reason()).isEqualTo(SqlCapture.DISABLED);
            assertThat(check(traced, RepeatedSelects.KIND)).satisfies(check -> {
                assertThat(check.status()).isEqualTo("INSUFFICIENT");
                assertThat(check.eligibleRequests()).isZero();
                assertThat(check.reason()).contains("No eligible work");
            });
            assertThat(check(reactive, RouteTimeBreakdown.KIND).status())
                    .as("an empty run has no eligible breakdown, and still says what it cannot capture")
                    .isEqualTo("INSUFFICIENT");
            assertThat(check(reactive, RouteTimeBreakdown.KIND).eligibleRequests())
                    .isZero();
            assertThat(check(reactive, RouteTimeBreakdown.KIND).reason()).contains(SqlCapture.NOT_RECORDED);
        }
    }

    @Test
    void anUnavailableRouteInventoryIsSaidRatherThanListingNoRoute() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(JournalAggregates.class, this::recordingAggregates);
            context.registerBean(MappingProvider.class, () -> new MappingProvider() {
                @Override
                public boolean available() {
                    return false;
                }

                @Override
                public List<MappingDto> mappings() {
                    return List.of();
                }
            });
            context.refresh();

            RuntimeInsightsReportDto report = controller(context).report();

            assertThat(report.notExercised()).isEmpty();
            assertThat(report.limitations()).contains(RuntimeInsightsService.ROUTE_INVENTORY_UNAVAILABLE);
        }
    }

    @Test
    void poolSizesAreReadFromAHikariDataSourceThroughSqlTraceItsProxyAndUnknownOtherwise() {
        try (HikariDataSource hikari = new HikariDataSource();
                GenericApplicationContext context = new GenericApplicationContext()) {
            hikari.setMaximumPoolSize(7);
            DataSource traced = SqlTracingProxies.wrapNamed(
                    hikari, new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5), "dataSource");
            context.registerBean("dataSource", DataSource.class, () -> traced);
            context.registerBean("plain", DataSource.class, SimpleDriverDataSource::new);
            context.registerBean("notADataSource", String.class, () -> "x");
            context.refresh();
            DataSourcePoolSizes sizes = new DataSourcePoolSizes(context);

            assertThat(sizes.apply("dataSource")).isEqualTo(7);
            assertThat(sizes.apply("plain")).isNull();
            assertThat(sizes.apply("notADataSource")).isNull();
            assertThat(sizes.apply("missing")).isNull();
            assertThat(sizes.apply(null)).isNull();
        }
    }

    private RuntimeInsightCheckDto eventLoopCheck(GenericApplicationContext context) {
        return check(context, EventLoopBlocking.KIND);
    }

    private RuntimeInsightCheckDto check(GenericApplicationContext context, String kind) {
        return controller(context).report().checks().stream()
                .filter(check -> check.kind().equals(kind))
                .findFirst()
                .orElseThrow();
    }

    private RuntimeInsightsController controller(GenericApplicationContext context) {
        return new RuntimeInsightsController(
                context,
                new BootUiProperties(),
                context.getBeanProvider(RuntimeJournal.class),
                context.getBeanProvider(JournalAggregates.class),
                context.getBeanProvider(MappingProvider.class),
                context.getBeanProvider(BeanProvider.class));
    }

    /** A recorder that traced a {@code DataSource}, as the post-processor leaves it for a JDBC application. */
    private static SqlTraceRecorder tracedRecorder() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
        recorder.registerDataSource("dataSource");
        return recorder;
    }
}
