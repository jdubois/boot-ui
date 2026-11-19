package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProxyBypassTests {

    private static final String REPOSITORY = "com.example.OrderRepository.save(OrderRepository.java:10)";
    private static final String PLACE = "com.example.OrderService.place(OrderService.java:20)";
    private static final String CHECKOUT = "com.example.OrderService.checkout(OrderService.java:12)";
    private static final String PRICE = "com.example.PriceService.price(PriceService.java:30)";
    private static final String NOTIFY = "com.example.Notifier.notifyShipped(Notifier.java:40)";
    private static final String UNKNOWN_CACHE = "com.example.DynamicPrices.fetch(DynamicPrices.java:10)";
    private static final String UNKNOWN_CACHE_REASON = "This method uses an unobservable custom cache resolver.";

    private static final ProxyBoundaries BOUNDARIES = frame -> Map.of(
                    PLACE, new ProxyBoundaries.Boundary(true, Set.of(), false),
                    PRICE, new ProxyBoundaries.Boundary(false, Set.of("prices"), false),
                    NOTIFY, new ProxyBoundaries.Boundary(false, Set.of(), true))
            .getOrDefault(frame, ProxyBoundaries.Boundary.NONE);

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;
    private long requestStartMillis = 1_000;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aTransactionalMethodWhoseStatementRanOutsideEveryTransactionWasBypassed() {
        // Self-invocation: checkout calls place on this, so no transaction opens.
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", REPOSITORY, PLACE, CHECKOUT)));
        // Through the bean: the proxy opened a transaction around the statement.
        request(
                "POST",
                "/api/orders",
                "http-1",
                List.of(
                        transaction(500, 2_000, "http-1"),
                        sql(
                                1_000,
                                "http-1",
                                REPOSITORY,
                                PLACE,
                                "com.example.OrderController.create(OrderController.java:9)")));

        RuntimeObservationDto finding = single(report());

        assertThat(finding.sentence())
                .isEqualTo("`POST /api/orders` ran `OrderService.place` without its `@Transactional` proxy in 1 of 2"
                        + " requests that ran an annotated method, called from `" + CHECKOUT + "`.");
        assertThat(finding.affected()).isEqualTo(1);
        assertThat(finding.whatToCheck().get(0)).contains("If", "on `this`", "managed bean");
        assertThat(finding.whatToCheck().get(1)).contains("ARCH-SPRING-004");
    }

    @Test
    void aMissingCacheEffectIsJudgedButAsyncThreadNamesAreNot() {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        request("GET", "/api/prices", "http-1", List.of(cache("prices"), sql(1_000, "http-1", PRICE)));
        request("POST", "/api/ship", "http-2", List.of(sql(1_000, "http-2", NOTIFY)));
        request("POST", "/api/ship", "http-2", List.of(sql(1_000, "task-1", NOTIFY)));

        RuntimeInsightsReportDto report = report();
        List<RuntimeObservationDto> findings = byKind(report);

        assertThat(findings)
                .extracting(RuntimeObservationDto::sentence)
                .containsExactly(
                        "`GET /api/prices` ran `PriceService.price` without its `@Cacheable(prices)` proxy in 1 of 2"
                                + " requests that ran an annotated method.");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.eligibleRequests()).isEqualTo(2);
                    assertThat(check.reason()).contains("2 requests or executions", "@Async");
                });
    }

    @Test
    void theSameCacheNameOnAnotherManagerDoesNotProveCacheableProxyExecution() {
        ProxyBoundaries boundaries = frame -> frame.equals(PRICE)
                ? new ProxyBoundaries.Boundary(false, Set.of("prices"), false, "primary", null)
                : ProxyBoundaries.Boundary.NONE;
        request("GET", "/api/prices", "http-1", List.of(cache("secondary", "prices"), sql(1_000, "http-1", PRICE)));
        request("GET", "/api/prices", "http-1", List.of(cache("primary", "prices"), sql(1_000, "http-1", PRICE)));
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setProxyBoundaries(boundaries);
        service.setCacheCapture(() -> new CacheCapture(Map.of("primary", 0L, "secondary", 0L), null, 0, null));

        RuntimeInsightsReportDto report = service.report();
        assertThat(single(report).sentence()).contains("without its `@Cacheable(prices)` proxy in 1 of 2 requests");
        assertThat(single(report).exemplarRequestIds()).containsExactly("r1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "http-1", "reactor-http-nio-1", "reused-worker"})
    void equalThreadNamesDoNotEstablishThatAsyncAdviceWasBypassed(String name) {
        request("POST", "/api/ship", name, List.of(sql(1_000, name, NOTIFY)));
        for (InsightsStack stack : List.of(InsightsStack.SPRING_MVC, InsightsStack.SPRING_WEBFLUX)) {
            RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
            service.setProxyBoundaries(BOUNDARIES);
            RuntimeInsightsReportDto report = service.report();

            assertThat(byKind(report)).isEmpty();
            assertThat(report.window().requests()).isEqualTo(1);
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> {
                        assertThat(check.eligibleRequests()).isZero();
                        assertThat(check.status()).isEqualTo("INSUFFICIENT");
                        assertThat(check.reason()).contains("@Async", "thread");
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = InsightsStack.class,
            names = {"SPRING_MVC", "SPRING_WEBFLUX"})
    void anUnassessableAsyncFrameDoesNotHideIndependentTransactionOrCacheEvidence(InsightsStack stack) {
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", NOTIFY, PLACE, CHECKOUT)));
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", NOTIFY, PRICE)));
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(BOUNDARIES);
        service.setCacheCapture(CacheCapture::capturing);

        RuntimeInsightsReportDto report = service.report();

        assertThat(byKind(report))
                .extracting(RuntimeObservationDto::sentence)
                .hasSize(2)
                .anyMatch(sentence -> sentence.contains("@Transactional"))
                .anyMatch(sentence -> sentence.contains("@Cacheable(prices)"))
                .noneMatch(sentence -> sentence.contains("`@Async`"));
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.eligibleRequests()).isEqualTo(2);
                    assertThat(check.reason()).contains("@Async");
                });
    }

    @Test
    void recommendationsDistinguishACapturedSameClassCallerFromAnotherCallerWithoutDiagnosingTheCause() {
        request("POST", "/self", "http-1", List.of(sql(1_000, "http-1", PLACE, CHECKOUT)));
        request(
                "POST",
                "/other",
                "http-1",
                List.of(sql(1_000, "http-1", PLACE, "com.example.OrderController.create(OrderController.java:9)")));

        assertThat(byKind(report()))
                .filteredOn(row -> row.subject().equals("POST /self"))
                .singleElement()
                .satisfies(row -> assertThat(row.whatToCheck().get(0)).contains("If", "this"));
        assertThat(byKind(report()))
                .filteredOn(row -> row.subject().equals("POST /other"))
                .singleElement()
                .satisfies(row -> assertThat(row.whatToCheck().get(0))
                        .contains("infrastructure", "managed bean")
                        .doesNotContain("self-invocation", "on `this`"));
    }

    @ParameterizedTest
    @CsvSource({
        "SPRING_MVC,true,false",
        "SPRING_MVC,true,true",
        "SPRING_MVC,false,false",
        "SPRING_MVC,false,true",
        "SPRING_WEBFLUX,true,false",
        "SPRING_WEBFLUX,true,true",
        "SPRING_WEBFLUX,false,false",
        "SPRING_WEBFLUX,false,true"
    })
    void unassessableNamelessCacheFrameDoesNotHideKnownOuterBoundary(
            InsightsStack stack, boolean transactionalOuter, boolean earlierAssessedStatement) {
        List<Child> children = new ArrayList<>();
        if (earlierAssessedStatement) {
            children.add(sql(900, "http-1", PRICE));
        }
        children.add(sql(1_000, "http-1", UNKNOWN_CACHE, transactionalOuter ? PLACE : PRICE));
        request("POST", "/api/mixed", "http-1", children);
        RuntimeInsightsReportDto report =
                serviceWithUnobservableCacheFrame(stack).report();
        List<RuntimeObservationDto> findings = byKind(report);
        assertThat(findings).hasSize(earlierAssessedStatement && transactionalOuter ? 2 : 1);
        assertThat(findings)
                .filteredOn(row -> row.sentence().contains(transactionalOuter ? "@Transactional" : "@Cacheable"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.affected()).isEqualTo(1);
                    assertThat(row.exemplarRequestIds()).containsExactly("r1");
                });
        assertCacheReason(report, UNKNOWN_CACHE_REASON);
    }

    @ParameterizedTest
    @CsvSource({"SPRING_MVC,true", "SPRING_MVC,false", "SPRING_WEBFLUX,true", "SPRING_WEBFLUX,false"})
    void namedUnobservableCacheFrameCannotHideItsUnknownReasonOrAKnownOuterBoundary(
            InsightsStack stack, boolean transactionalOuter) {
        request(
                "POST",
                "/api/mixed",
                "http-1",
                List.of(sql(1_000, "http-1", UNKNOWN_CACHE, transactionalOuter ? PLACE : PRICE)));
        RuntimeInsightsReportDto report = serviceWithUnobservableCacheFrame(
                        stack, new ProxyBoundaries.Boundary(false, Set.of("prices"), false, null, UNKNOWN_CACHE_REASON))
                .report();
        assertThat(byKind(report))
                .singleElement()
                .satisfies(row ->
                        assertThat(row.sentence()).contains(transactionalOuter ? "@Transactional" : "@Cacheable"));
        assertCacheReason(report, UNKNOWN_CACHE_REASON);
    }

    @ParameterizedTest
    @CsvSource({"SPRING_MVC,true", "SPRING_MVC,false", "SPRING_WEBFLUX,true", "SPRING_WEBFLUX,false"})
    void anUnobservableNamedCachePartStillAllowsTheSameFramesSupportedBoundary(
            InsightsStack stack, boolean transactionalFrame) {
        request(
                "POST",
                "/api/mixed",
                "http-1",
                List.of(cache("prices"), sql(1_000, "http-1", UNKNOWN_CACHE, transactionalFrame ? NOTIFY : PLACE)));
        RuntimeInsightsReportDto report = serviceWithUnobservableCacheFrame(
                        stack,
                        new ProxyBoundaries.Boundary(
                                transactionalFrame, Set.of("prices"), !transactionalFrame, null, UNKNOWN_CACHE_REASON))
                .report();
        assertThat(byKind(report)).singleElement().satisfies(row -> {
            assertThat(row.sentence())
                    .contains(transactionalFrame ? "DynamicPrices.fetch" : "OrderService.place", "@Transactional");
            assertThat(row.sentence()).doesNotContain("`@Async`");
        });
        assertCacheReason(report, UNKNOWN_CACHE_REASON);
    }

    @ParameterizedTest
    @EnumSource(
            value = InsightsStack.class,
            names = {"SPRING_MVC", "SPRING_WEBFLUX"})
    void nearestJudgeableCacheBoundaryStillStopsBeforeAnOuterBoundary(InsightsStack stack) {
        request(
                "POST",
                "/api/mixed",
                "http-1",
                List.of(cache("prices"), sql(1_000, "http-1", UNKNOWN_CACHE, PRICE, PLACE)));
        RuntimeInsightsReportDto report =
                serviceWithUnobservableCacheFrame(stack).report();
        assertThat(byKind(report)).isEmpty();
        assertCacheReason(report, UNKNOWN_CACHE_REASON);
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.eligibleRequests()).isEqualTo(1));
    }

    private RuntimeInsightsService serviceWithUnobservableCacheFrame(InsightsStack stack) {
        return serviceWithUnobservableCacheFrame(
                stack, new ProxyBoundaries.Boundary(false, Set.of(), false, null, UNKNOWN_CACHE_REASON));
    }

    private RuntimeInsightsService serviceWithUnobservableCacheFrame(
            InsightsStack stack, ProxyBoundaries.Boundary unobservable) {
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setCacheCapture(CacheCapture::capturing);
        service.setProxyBoundaries(frame -> frame.equals(UNKNOWN_CACHE) ? unobservable : BOUNDARIES.resolve(frame));
        return service;
    }

    @ParameterizedTest
    @EnumSource(
            value = InsightsStack.class,
            names = {"SPRING_MVC", "SPRING_WEBFLUX"})
    void cacheCaptureCapabilityChangesInvalidateTheReportWithoutNewJournalEvents(InsightsStack stack) {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        AtomicLong clock = new AtomicLong(2_000);
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10, clock::get);
        recorder.setRuntimeEventSink(journal);
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(BOUNDARIES);
        service.setCacheCapture(() -> CacheCapture.of(recorder));

        RuntimeInsightsReportDto uninstrumented = service.report();
        assertThat(byKind(uninstrumented)).isEmpty();
        assertCacheReason(uninstrumented, CacheCapture.NOT_RECORDED);
        assertThat(service.report()).isSameAs(uninstrumented);

        recorder.markInstrumentedManager();
        RuntimeInsightsReportDto installed = service.report();
        assertThat(installed).isNotSameAs(uninstrumented);
        assertThat(byKind(installed)).isEmpty();
        assertCacheReason(installed, CacheCapture.BEFORE_WINDOW);

        requestStartMillis = 2_000;
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        assertThat(byKind(service.report()))
                .as("the installation millisecond is not ordered")
                .isEmpty();
        requestStartMillis = 2_001;
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        RuntimeInsightsReportDto captured = service.report();
        assertThat(single(captured).sentence()).contains("@Cacheable(prices)");
        assertThat(single(captured).exemplarRequestIds()).containsExactly("r3");
        assertThat(service.report()).isSameAs(captured);

        clock.set(3_000);
        recorder.suspendForLowFootprint();
        RuntimeInsightsReportDto paused = service.report();
        assertThat(byKind(paused)).isEmpty();
        assertCacheReason(paused, CacheCapture.PAUSED);
        assertThat(service.insight(single(captured).id()).available()).isFalse();

        requestStartMillis = 3_100;
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        clock.set(4_000);
        recorder.resumeFromLowFootprint();
        assertThat(byKind(service.report())).isEmpty();
        assertCacheReason(service.report(), CacheCapture.BEFORE_WINDOW);
        assertThat(service.insight(single(captured).id()).available()).isFalse();
        requestStartMillis = 4_001;
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        long watermark = journal.status().lastSequence();
        RuntimeObservationDto resumed = single(service.report());
        assertThat(resumed.id()).isEqualTo(single(captured).id());
        assertThat(resumed.exemplarRequestIds()).containsExactly("r5");
        assertThat(journal.status().lastSequence()).isEqualTo(watermark);
    }

    @ParameterizedTest
    @CsvSource({
        "SPRING_MVC,pause",
        "SPRING_MVC,recording-failure",
        "SPRING_MVC,correlation",
        "SPRING_WEBFLUX,pause",
        "SPRING_WEBFLUX,recording-failure",
        "SPRING_WEBFLUX,correlation"
    })
    void captureTransitionsBetweenProofAndEntryReadsWithholdAbsenceButKeepRetainedPresence(
            InsightsStack stack, String transition) {
        AtomicLong clock = new AtomicLong(1_000);
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10, clock::get);
        recorder.setRuntimeEventSink(journal);
        recorder.markInstrumentedManager();
        requestStartMillis = 1_100;
        request("GET", "/api/prices", "http-1", List.of(cache("prices"), sql(1_000, "http-1", PRICE)));
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(BOUNDARIES);
        AtomicBoolean interleave = new AtomicBoolean(true);
        service.setCacheCapture(() -> {
            CacheCapture before = CacheCapture.of(recorder);
            if (interleave.getAndSet(false)) {
                clock.set(2_000);
                switch (transition) {
                    case "pause" -> recorder.suspendForLowFootprint();
                    case "recording-failure" ->
                        recorder.recordMiss("", "prices", new Object() {
                            @Override
                            public String toString() {
                                throw new IllegalStateException("private key representation");
                            }
                        });
                    case "correlation" -> recorder.setCorrelationContextProvider(() -> CorrelationContext.NONE);
                    default -> throw new AssertionError(transition);
                }
                requestStartMillis = 1_500;
                request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
            }
            return before;
        });

        RuntimeInsightsReportDto raced = service.report();
        assertThat(raced.window().requests()).isEqualTo(2);
        assertThat(byKind(raced)).isEmpty();
        assertCacheReason(raced, CacheCapture.CHANGED_DURING_READ);
        assertThat(raced.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.eligibleRequests())
                        .as("retained positive access stays readable")
                        .isEqualTo(1));
        assertThat(byKind(service.report()))
                .as("the earlier request does not become a bypass on a later read")
                .isEmpty();
        assertThat(raced.toString()).doesNotContain("private key representation");
    }

    @ParameterizedTest
    @EnumSource(
            value = InsightsStack.class,
            names = {"SPRING_MVC", "SPRING_WEBFLUX"})
    void disabledOrMissingCacheCaptureCannotProveBypassButTransactionEvidenceStillCan(InsightsStack stack) {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", PLACE)));
        AtomicReference<CacheActivityRecorder> recorder = new AtomicReference<>(new CacheActivityRecorder(false, 10));
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(BOUNDARIES);
        service.setCacheCapture(() -> CacheCapture.of(recorder.get()));

        RuntimeInsightsReportDto disabled = service.report();
        assertThat(single(disabled).sentence()).contains("@Transactional").doesNotContain("@Cacheable");
        assertCacheReason(disabled, CacheCapture.DISABLED);

        recorder.set(null);
        RuntimeInsightsReportDto missing = service.report();
        assertThat(single(missing).sentence()).contains("@Transactional");
        assertCacheReason(missing, CacheCapture.NOT_RECORDED);
        assertThat(missing).isNotSameAs(disabled);

        service.setCacheCapture(() -> null);
        assertCacheReason(service.report(), CacheCapture.NOT_RECORDED);
        assertThat(single(service.report()).sentence()).contains("@Transactional");
        service.setCacheCapture(() -> {
            throw new IllegalStateException("sensitive supplier failure text");
        });
        RuntimeInsightsReportDto failed = service.report();
        assertThat(single(failed).sentence()).contains("@Transactional");
        assertCacheReason(failed, "IllegalStateException");
        assertThat(failed.checks().toString()).doesNotContain("sensitive supplier failure text");
        service.setCacheCapture(() -> {
            throw new NoClassDefFoundError("missing optional type");
        });
        assertCacheReason(service.report(), "NoClassDefFoundError");
        assertThat(single(service.report()).sentence()).contains("@Transactional");
    }

    @Test
    void disabledCachePanelsAndQuarkusKeepTheirOwnReasonsInsteadOfTheCaptureReason() {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals("cache"), InsightsStack.SPRING_MVC, null);
        service.setProxyBoundaries(BOUNDARIES);
        service.setCacheCapture(() -> CacheCapture.notRecorded(CacheCapture.DISABLED));
        assertThat(byKind(service.report())).isEmpty();
        assertCacheReason(service.report(), "disabled");
        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason()).doesNotContain(CacheCapture.DISABLED));

        RuntimeInsightsService quarkus = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null);
        quarkus.setCacheCapture(() -> CacheCapture.notRecorded(CacheCapture.NOT_RECORDED));
        assertThat(byKind(quarkus.report())).isEmpty();
        assertThat(quarkus.report().checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
                    assertThat(check.reason()).contains("ArC intercepts").doesNotContain(CacheCapture.NOT_RECORDED);
                });
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false"})
    void captureCapabilityDoesNotOverrideJournalSourceSelectionOrPanelVisibility(boolean recorded, boolean visible) {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        ProxyBypass bypass = new ProxyBypass();
        bypass.setBoundaries(BOUNDARIES);
        InsightsSnapshot snapshot = InsightsSnapshot.of(
                        journal.entries(),
                        journal.status(),
                        RouteTemplateResolver.empty(),
                        source -> recorded || source != JournalSource.CACHE,
                        source -> visible || source != JournalSource.CACHE,
                        InsightsStack.SPRING_MVC,
                        null)
                .withCacheCapture(CacheCapture.capturing());
        assertThat(bypass.evaluate(snapshot).findings()).isEmpty();
    }

    private static void assertCacheReason(RuntimeInsightsReportDto report, String reason) {
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason()).contains(reason));
    }

    @Test
    void aRequestThatLostItsCacheAccessToAClearIsLeftOutRatherThanReportedAsBypassed() throws InterruptedException {
        CorrelationContext context = CorrelationContext.forRequest("r1");
        journal.offer(RuntimeEvent.of(
                JournalSource.CACHE, 5_001, 0, context, "http-1", null, false, new CachePayload("prices", "MISS")));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        // Clear recording while the request is still running: its cache access is gone, its statement is not.
        journal.clear();
        Child statement = sql(1_000, "http-1", PRICE);
        journal.offer(RuntimeEvent.of(
                statement.source(), 5_002, statement.nanos(), context, "http-1", null, false, statement.payload()));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                5_000,
                5_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/api/prices", "/api/prices", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeInsightsReportDto report = report();

        assertThat(byKind(report)).isEmpty();
        assertThat(report.window().requests()).isZero();
        assertThat(report.limitations())
                .contains("1 request or execution started before events the journal evicted or cleared, so it is left"
                        + " out: some of its events may be missing.");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason())
                        .as("an empty result is not read as nothing found")
                        .contains(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS));
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(HeapGrowthAfterGc.KIND))
                .singleElement()
                .satisfies(check -> assertThat(String.valueOf(check.reason()))
                        .as("it examines collections, not requests")
                        .doesNotContain(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS));
    }

    @Test
    void itDoesNotApplyOnQuarkusWithoutAResolverOrWhenTheResolverSaysWhy() {
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", REPOSITORY, PLACE, CHECKOUT)));

        assertThat(reason(InsightsStack.QUARKUS, BOUNDARIES)).contains("ArC intercepts");
        assertThat(reason(InsightsStack.SPRING_MVC, null)).contains("No resolver");
        assertThat(reason(InsightsStack.SPRING_MVC, new ProxyBoundaries() {
                    @Override
                    public Boundary resolve(String frame) {
                        return Boundary.NONE;
                    }

                    @Override
                    public String notApplicable() {
                        return "AspectJ weaving.";
                    }
                }))
                .isEqualTo("AspectJ weaving.");
        assertThat(ProxyBypass.method(PLACE)).isEqualTo("OrderService.place");
    }

    private String reason(InsightsStack stack, ProxyBoundaries boundaries) {
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(boundaries);
        return service.report().checks().stream()
                .filter(check -> check.kind().equals(ProxyBypass.KIND))
                .findFirst()
                .orElseThrow()
                .reason();
    }

    private RuntimeInsightsReportDto report() {
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setProxyBoundaries(BOUNDARIES);
        service.setCacheCapture(CacheCapture::capturing);
        return service.report();
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(ProxyBypass.KIND))
                .toList();
    }

    private static RuntimeObservationDto single(RuntimeInsightsReportDto report) {
        List<RuntimeObservationDto> findings = byKind(report);
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    private static Child sql(long completedNanos, String thread, String... frames) {
        return new Child(
                JournalSource.SQL,
                thread,
                100,
                new SqlPayload(
                        "insert into orders values (?)",
                        frames[0],
                        "db",
                        false,
                        ApplicationFrames.of(List.of(frames)),
                        null,
                        completedNanos));
    }

    private static Child transaction(long startNanos, long durationNanos, String thread) {
        return new Child(
                JournalSource.TRANSACTION,
                thread,
                durationNanos,
                new TransactionPayload("OrderService.place", false, false, false, startNanos));
    }

    private static Child cache(String name) {
        return cache("", name);
    }

    private static Child cache(String managerName, String cacheName) {
        return new Child(JournalSource.CACHE, "http-1", 0, new CachePayload(cacheName, "MISS", null, 0L, managerName));
    }

    private void request(String method, String template, String thread, List<Child> children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        List<RuntimeEvent> events = new ArrayList<>();
        for (Child child : children) {
            events.add(RuntimeEvent.of(
                    child.source(),
                    requestStartMillis,
                    child.nanos(),
                    context,
                    child.thread(),
                    null,
                    false,
                    child.payload()));
        }
        events.add(RuntimeEvent.of(
                JournalSource.HTTP,
                requestStartMillis,
                5_000_000,
                context,
                thread,
                null,
                false,
                new HttpPayload(method, template, template, null, 200)));
        events.forEach(journal::offer);
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, String thread, long nanos, RuntimeEventPayload payload) {}
}
