package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemorySeverityCountDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleRefusals;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ThreadData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryScannerTests {

    private static final int RULE_COUNT = 31;
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-06T08:00:00Z"), ZoneOffset.UTC);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 10000})
    void retainsCountedPoolObservationsWithoutSamplingOrCountingInternalSeverityChecks(int retentionLimit) {
        AtomicInteger collections = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    collections.incrementAndGet();
                    return bloatedCollectionsContext();
                },
                CLOCK);
        scanner.setViolationRetentionLimit(() -> retentionLimit);
        MemoryReport report = scanner.scan();
        String scanId = report.violationDetails().scanId();
        String ruleId = "MEM-CONTENT-002";
        List<String> expected = List.of(
                MemoryRuleSupport.detail(
                        "java.util.ArrayList occupies 104.0 MiB (17% of histogram bytes, shallow) across 2000000 instances; confirm this collection is bounded."),
                MemoryRuleSupport.detail(
                        "java.util.LinkedList occupies 103.0 MiB (17% of histogram bytes, shallow) across 2000000 instances; confirm this collection is bounded."),
                MemoryRuleSupport.detail(
                        "java.util.HashMap occupies 102.0 MiB (17% of histogram bytes, shallow) across 2000000 instances; confirm this collection is bounded."),
                MemoryRuleSupport.detail(
                        "java.util.LinkedHashMap occupies 101.0 MiB (16% of histogram bytes, shallow) across 2000000 instances; confirm this collection is bounded."),
                MemoryRuleSupport.detail(
                        "java.util.TreeMap occupies 100.0 MiB (16% of histogram bytes, shallow) across 2000000 instances; confirm this collection is bounded."));
        assertThat(report.results())
                .filteredOn(result -> result.id().equals(ruleId))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(5);
                    assertThat(result.sampleViolations()).containsExactlyElementsOf(expected);
                });
        int total = report.results().stream()
                .mapToInt(MemoryRuleResultDto::violationCount)
                .sum();
        assertThat(report.violationDetails().total()).isEqualTo(total);
        assertThat(report.violationDetails().retained()).isEqualTo(Math.min(total, retentionLimit));
        var page = scanner.ruleViolations(ruleId, scanId, 0, 3);
        AdvisorRuleRefusals.assertKnownAndUnknownRulesAreToldApart(
                AdvisorScanState.ruleIds(
                        MemoryRuleRegistry.activeRules(),
                        rule -> rule.definition().id()),
                report.results().stream().map(evaluated -> evaluated.id()).toList(),
                report.violationDetails().scanId(),
                (asked, scan) -> scanner.ruleViolations(asked, scan, 0, 1));
        assertThat(page.violationCount()).isEqualTo(5);
        if (retentionLimit > total) {
            assertThat(page.violations()).containsExactlyElementsOf(expected.subList(0, 3));
            assertThat(page.page().hasMore()).isTrue();
            var last = scanner.ruleViolations(ruleId, scanId, 3, 3);
            assertThat(last.violations()).containsExactlyElementsOf(expected.subList(3, 5));
            assertThat(last.page().hasMore()).isFalse();
            assertThat(last.truncated()).isFalse();
        } else {
            assertThat(page.truncated()).isTrue();
            assertThat(page.violations()).containsExactlyElementsOf(expected.subList(0, page.retainedCount()));
        }
        assertThat(scanner.applyDismissals(report, java.util.Set.of(ruleId)).violationDetails())
                .isEqualTo(report.violationDetails());
        assertThat(scanner.lastReport()).isSameAs(report);
        assertThat(collections).hasValue(1);
    }

    private static MemoryContext bloatedCollectionsContext() {
        MemoryContext baseline = healthyContext();
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "java.util.ArrayList", 2_000_000, 104 * MB),
                new HeapClassHistogramEntryDto(2, "java.util.LinkedList", 2_000_000, 103 * MB),
                new HeapClassHistogramEntryDto(3, "java.util.HashMap", 2_000_000, 102 * MB),
                new HeapClassHistogramEntryDto(4, "java.util.LinkedHashMap", 2_000_000, 101 * MB),
                new HeapClassHistogramEntryDto(5, "java.util.TreeMap", 2_000_000, 100 * MB),
                new HeapClassHistogramEntryDto(6, "byte[]", 1_000_000, 90 * MB));
        HeapContentData heapContent = new HeapContentData(true, histogram, 11_000_000, 600 * MB);
        return new MemoryContext(
                baseline.memory(), baseline.threads(), heapContent, baseline.classLoading(), baseline.runtime());
    }

    @Test
    void initialReportIsNotScanned() {
        MemoryScanner scanner = new MemoryScanner(() -> healthyContext(), CLOCK);

        MemoryReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.localOnly()).isTrue();
    }

    @Test
    void healthyRuntimeEvaluatesAllRulesWithoutViolations() {
        MemoryScanner scanner = new MemoryScanner(() -> healthyContext(), CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.summary().heapUsedPercent()).isEqualTo(25);
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).isNotEmpty();
    }

    @Test
    void completionExcludesSkippedAndFailedRulesAndSurvivesDismissal() {
        MemoryEvaluation skipped = new RecentGcOverheadRule().evaluateWithEvidence(healthyContext());
        MemoryRule broken =
                new AbstractMemoryRule(MemoryRuleRegistry.activeRules().get(0).definition()) {
                    @Override
                    MemoryEvaluation evaluateRule(MemoryContext context) {
                        throw new IllegalStateException("unavailable");
                    }
                };
        MemoryEvaluation failed = broken.evaluateWithEvidence(healthyContext());
        assertThat(MemoryScanner.evidence(List.of(skipped, failed)).usable()).isFalse();
        assertThat(MemoryScanner.evidence(List.of(skipped, failed)).coverageComplete())
                .isFalse();
        var partial = MemoryScanner.evidence(
                List.of(skipped, failed, new HighHeapUtilizationRule().evaluateWithEvidence(healthyContext())));
        assertThat(partial.usable()).isTrue();
        assertThat(partial.coverageComplete()).isFalse();
        assertThat(MemoryScanner.evidence(List.of(new BigObjectsRule().evaluateWithEvidence(healthyContext())))
                        .usable())
                .isFalse();
        MemoryScanner scanner = new MemoryScanner(MemoryScannerTests::healthyContext, CLOCK);
        MemoryReport report = scanner.scan();
        assertThat(scanner.applyDismissals(report, java.util.Set.of("MEM-T-003"))
                        .evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void duplicateScanDoesNotQueueOrAdvanceTrendState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger collections = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    collections.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return healthyContext();
                },
                CLOCK);
        Thread winner = new Thread(scanner::scan);
        winner.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(scanner::scan).isInstanceOfSatisfying(ActionBusyException.class, failure -> {
            assertThat(failure.result().operation()).isEqualTo("memory.scan");
            assertThat(failure.result().activeOperation()).isEqualTo("memory.scan");
        });
        assertThat(collections).hasValue(1);

        release.countDown();
        winner.join(5000);
        assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
        assertThat(collections).hasValue(2);
    }

    @Test
    void deadlockProducesCriticalViolationSortedFirst() {
        MemoryData memory = healthyMemory();
        ThreadData threads = new ThreadData(
                40,
                40,
                10,
                true,
                true,
                List.of(11L, 12L),
                List.of(new ThreadStateCountDto("BLOCKED", 12), new ThreadStateCountDto("RUNNABLE", 28)),
                List.of());
        MemoryContext context = new MemoryContext(
                memory, threads, HeapContentData.unavailable(), new ClassLoadingData(8000, 8000, 0), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-THREAD-001", "MEM-THREAD-002");
        assertThat(report.results().get(0).severity()).isEqualTo("CRITICAL");
        assertThat(report.results().get(0).id()).isEqualTo("MEM-THREAD-001");
        assertThat(severityCount(report, "CRITICAL")).isEqualTo(1);
    }

    @Test
    void highHeapUtilizationIsFlagged() {
        MemoryData memory = new MemoryData(
                990 * MB,
                1 * GB,
                1 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of("-Xmx1g"),
                List.of("G1 Young Generation"),
                null,
                null);
        MemoryContext context = new MemoryContext(
                memory, ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-HEAP-001");
    }

    @Test
    void collectionBloatFromHistogramIsFlagged() {
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "java.util.HashMap$Node", 2_000_000, 120 * MB),
                new HeapClassHistogramEntryDto(2, "byte[]", 1000, 30 * MB));
        HeapContentData heapContent = new HeapContentData(true, histogram, 2_001_000, 150 * MB);
        MemoryContext context = new MemoryContext(
                healthyMemory(), ThreadData.empty(), heapContent, ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-CONTENT-002");
        assertThat(report.summary().histogramAvailable()).isTrue();
    }

    @Test
    void heapContentRulesSkipGracefullyWhenHistogramAbsent() {
        MemoryScanner scanner = new MemoryScanner(() -> healthyContext(), CLOCK);

        MemoryReport report = scanner.scan();

        // Skipped rules are not reported as violations.
        assertThat(report.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-CONTENT-001", "MEM-CONTENT-002");
        assertThat(report.scan().status()).isEqualTo("SCANNED");
    }

    @Test
    void collectorFailureProducesErrorReport() {
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    throw new IllegalStateException("beans unavailable");
                },
                CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.scan().message()).contains("beans unavailable");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void retiredCpuThreadAndClassLoadingChurnRulesAreNotFlaggedAndDismissalsStayValid() {
        ThreadInfoDto hot = new ThreadInfoDto(
                42,
                "worker-1",
                "RUNNABLE",
                5,
                false,
                false,
                290_000L,
                100_000L,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                List.of());
        ThreadData threads = new ThreadData(
                20, 20, 5, true, false, List.of(), List.of(new ThreadStateCountDto("RUNNABLE", 20)), List.of(hot));
        MemoryContext context = new MemoryContext(
                healthyMemory(),
                threads,
                HeapContentData.unavailable(),
                new ClassLoadingData(9000, 60_000, 51_000),
                healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContainAnyElementsOf(MemoryScanner.RETIRED_RULE_IDS);
        assertThat(scanner.applyDismissals(report, MemoryScanner.RETIRED_RULE_IDS))
                .isEqualTo(report);
    }

    @Test
    void configuredMemoryNearContainerLimitIsFlagged() {
        MemoryData memory = new MemoryData(
                300 * MB,
                700 * MB,
                1 * GB,
                120 * MB,
                150 * MB,
                256 * MB,
                List.of(),
                40 * MB,
                50 * MB,
                64,
                256 * MB,
                List.of("-Xmx1g"),
                List.of("G1 Young Generation"),
                1 * GB,
                null);
        ThreadData threads = new ThreadData(
                30, 30, 5, false, false, List.of(), List.of(new ThreadStateCountDto("RUNNABLE", 30)), List.of());
        MemoryContext context = new MemoryContext(
                memory, threads, HeapContentData.unavailable(), ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-FOOTPRINT-001");
    }

    @Test
    void highGcOverheadIsFlagged() {
        RuntimeData runtime = new RuntimeData(700_000, 100_000, 5000, 0, -1, MB, 4, -1, -1, null);
        MemoryContext context = new MemoryContext(
                healthyMemory(), ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), runtime);
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-002");
    }

    @Test
    void compressedOopsCliffIsFlagged() {
        MemoryData memory = new MemoryData(
                10 * GB,
                20 * GB,
                40 * GB,
                128 * MB,
                160 * MB,
                512 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of("-Xmx40g"),
                List.of("G1 Young Generation"),
                null,
                null);
        MemoryContext context = new MemoryContext(
                memory, ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-HEAP-004");
    }

    @Test
    void pendingFinalizationBacklogIsFlagged() {
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 5000, -1, MB, 4, -1, -1, null);
        MemoryContext context = new MemoryContext(
                healthyMemory(), ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), runtime);
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-HEAP-006");
    }

    @Test
    void unboundedMetaspaceInContainerIsFlagged() {
        MemoryData memory = new MemoryData(
                512 * MB,
                1 * GB,
                1 * GB,
                200 * MB,
                220 * MB,
                -1,
                List.of(new MemoryPoolSnapshot("Metaspace", 150 * MB, 160 * MB, -1)),
                16 * MB,
                16 * MB,
                64,
                256 * MB,
                List.of("-Xmx1g"),
                List.of("G1 Young Generation"),
                2 * GB,
                null);
        MemoryContext context = new MemoryContext(
                memory, ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-POOL-004");
    }

    @Test
    void unequalInitialAndMaxHeapForLowLatencyCollectorIsFlagged() {
        MemoryData memory = new MemoryData(
                1 * GB,
                2 * GB,
                4 * GB,
                128 * MB,
                160 * MB,
                512 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of("-Xmx4g", "-Xms1g"),
                List.of("ZGC Cycles", "ZGC Pauses"),
                null,
                null);
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 0, 1 * GB, MB, 4, -1, -1, null);
        MemoryContext context = new MemoryContext(
                memory, ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty(), runtime);
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-HEAP-005");
    }

    private static int severityCount(MemoryReport report, String severity) {
        return report.severityCounts().stream()
                .filter(count -> count.severity().equals(severity))
                .mapToInt(MemorySeverityCountDto::count)
                .findFirst()
                .orElse(0);
    }

    private static MemoryContext healthyContext() {
        return new MemoryContext(
                healthyMemory(),
                new ThreadData(
                        30,
                        35,
                        10,
                        true,
                        false,
                        List.of(),
                        List.of(new ThreadStateCountDto("RUNNABLE", 20), new ThreadStateCountDto("WAITING", 10)),
                        List.of()),
                HeapContentData.unavailable(),
                new ClassLoadingData(9000, 9500, 500),
                healthyRuntime());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static RuntimeData healthyRuntime() {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, -1, -1, null);
    }

    private static MemoryData healthyMemory() {
        return new MemoryData(
                512 * MB,
                1 * GB,
                2 * GB,
                128 * MB,
                160 * MB,
                512 * MB,
                List.of(
                        new MemoryPoolSnapshot("G1 Old Gen", 200 * MB, 512 * MB, 2 * GB),
                        new MemoryPoolSnapshot("Metaspace", 80 * MB, 96 * MB, 256 * MB),
                        new MemoryPoolSnapshot("CodeHeap 'non-nmethods'", 8 * MB, 16 * MB, 64 * MB)),
                16 * MB,
                16 * MB,
                64,
                256 * MB,
                List.of("-Xmx2g"),
                List.of("G1 Young Generation", "G1 Old Generation"),
                null,
                null);
    }

    @Test
    void analysisErrorsKeepsOnlyErrorResultsSortedById() {
        MemoryRuleResultDto pass = result("MEM-T-001", MemoryRuleSupport.PASS);
        MemoryRuleResultDto violation = result("MEM-T-002", MemoryRuleSupport.VIOLATION);
        MemoryRuleResultDto errorB = result("MEM-T-004", MemoryRuleSupport.ERROR);
        MemoryRuleResultDto errorA = result("MEM-T-003", MemoryRuleSupport.ERROR);
        MemoryRuleResultDto skipped = result("MEM-T-005", MemoryRuleSupport.SKIPPED);

        List<MemoryRuleResultDto> errors =
                MemoryScanner.analysisErrors(List.of(pass, violation, errorB, errorA, skipped));

        assertThat(errors).extracting(MemoryRuleResultDto::id).containsExactly("MEM-T-003", "MEM-T-004");
        assertThat(errors).extracting(MemoryRuleResultDto::status).containsOnly(MemoryRuleSupport.ERROR);
    }

    @Test
    void bootUiOffloadAndLowFootprintGcsAreExcludedFromMemGc002And003And005And006() {
        var uptime = new java.util.concurrent.atomic.AtomicLong(600_000L);
        var gcTime = new java.util.concurrent.atomic.AtomicLong(10_000L);
        var gcCount = new java.util.concurrent.atomic.AtomicLong(100L);
        var oldGenCount = new java.util.concurrent.atomic.AtomicLong(0L);
        var latestEvent = new java.util.concurrent.atomic.AtomicReference<>(
                new MemoryContext.GcEvent(1, 500_000L, 100L, "G1 Young Generation"));

        BootUiDiagnosticGcTracker tracker = new BootUiDiagnosticGcTracker(
                () -> new MemoryContext.GcSample(
                        uptime.get(),
                        gcTime.get(),
                        gcCount.get(),
                        java.util.Map.of(
                                "G1 Young Generation",
                                gcCount.get() - oldGenCount.get(),
                                "G1 Old Generation",
                                oldGenCount.get())),
                latestEvent::get);

        MemoryOffloadService offload = new MemoryOffloadService(
                List::of,
                () -> 512 * MB,
                () -> {
                    // Simulate System.gc() taking 55,000 ms (which would push lifetime GC > 10%, recent > 25%,
                    // G1 Old Generation +1, and latest event duration 55,000 ms >= 1,000 ms).
                    uptime.addAndGet(55_000L);
                    gcTime.addAndGet(55_000L);
                    gcCount.incrementAndGet();
                    oldGenCount.incrementAndGet();
                    latestEvent.set(new MemoryContext.GcEvent(2, uptime.get(), 55_000L, "G1 Old Generation"));
                },
                () -> false,
                System::nanoTime,
                tracker);
        LowFootprintService lowFootprint = new LowFootprintService(List::of, offload, () -> false, uptime::get);

        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    MemoryContext.GcSample pre = new MemoryContext.GcSample(
                            uptime.get(),
                            gcTime.get(),
                            gcCount.get(),
                            java.util.Map.of(
                                    "G1 Young Generation",
                                    gcCount.get() - oldGenCount.get(),
                                    "G1 Old Generation",
                                    oldGenCount.get()));
                    MemoryContext.GcEvent preEvent = latestEvent.get();
                    // Simulate scan's own GC.class_histogram adding another 20,000 ms Full GC
                    long postUptime = uptime.addAndGet(20_000L);
                    long postGcTime = gcTime.addAndGet(20_000L);
                    long postGcCount = gcCount.incrementAndGet();
                    long postOldGen = oldGenCount.incrementAndGet();
                    MemoryContext.GcEvent postEvent =
                            new MemoryContext.GcEvent(postGcCount, postUptime, 20_000L, "G1 Old Generation");
                    latestEvent.set(postEvent);
                    MemoryContext.GcSample post = new MemoryContext.GcSample(
                            postUptime,
                            postGcTime,
                            postGcCount,
                            java.util.Map.of(
                                    "G1 Young Generation", postGcCount - postOldGen, "G1 Old Generation", postOldGen));
                    RuntimeData runtime = new RuntimeData(
                            pre.uptimeMillis(),
                            pre.gcTimeMillis(),
                            pre.gcCount(),
                            0,
                            -1,
                            MB,
                            4,
                            -1,
                            -1,
                            null,
                            -1,
                            preEvent.durationMillis(),
                            preEvent.collectorName());
                    return new MemoryContext(
                            healthyMemory(),
                            ThreadData.empty(),
                            HeapContentData.unavailable(),
                            MemoryContext.PostGcHeapData.unavailable(),
                            new ClassLoadingData(9000, 9500, 500),
                            runtime,
                            pre,
                            post,
                            preEvent,
                            postEvent,
                            MemoryContext.GcTrend.unavailable());
                },
                CLOCK,
                tracker);

        // Scan 1 seeds baseline; its own 20s histogram GC must not trigger MEM-GC-002
        MemoryReport firstReport = scanner.scan();
        assertThat(firstReport.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-003", "MEM-GC-005", "MEM-GC-006");

        // Advance 30s of normal application time (only 100ms of Young GC) and trigger Low-Footprint / Offload
        uptime.addAndGet(30_000L);
        gcTime.addAndGet(100L);
        gcCount.incrementAndGet();
        lowFootprint.enable();

        // Scan 2 must ignore both Scan 1's 20s histogram GC and Low-Footprint/Offload's 55s Full GC
        // (even if GcEventSource delivered an asynchronous "System.gc()" notification for that offload GC).
        tracker.recordGcNotification("G1 Old Generation", 2L, "end of major GC", "System.gc()", 675_000L, 55_000L);
        MemoryReport secondReport = scanner.scan();
        assertThat(secondReport.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-003", "MEM-GC-005", "MEM-GC-006", "MEM-GC-009");

        // Positive control: a genuine application Full GC (8,000 ms over 20,000 ms window) after Scan 2
        // still triggers MEM-GC-003, MEM-GC-005, and MEM-GC-006, and an external System.gc() triggers MEM-GC-009.
        uptime.addAndGet(20_000L);
        gcTime.addAndGet(8_000L);
        long appGcId = gcCount.incrementAndGet();
        long appOldGenId = oldGenCount.incrementAndGet();
        latestEvent.set(new MemoryContext.GcEvent(appGcId, uptime.get(), 8_000L, "G1 Old Generation"));
        tracker.recordGcNotification(
                "G1 Old Generation", appOldGenId, "end of major GC", "System.gc()", uptime.get(), 8_000L);

        MemoryReport thirdReport = scanner.scan();
        assertThat(thirdReport.results())
                .extracting(MemoryRuleResultDto::id)
                .contains("MEM-GC-003", "MEM-GC-005", "MEM-GC-006", "MEM-GC-009")
                .doesNotContain("MEM-GC-002");
    }

    @Test
    void failedScanPreservesCumulativeHistogramExclusionsOnNextSuccessfulScan() {
        AtomicInteger call = new AtomicInteger();
        MemoryContext.GcSample pre1 = new MemoryContext.GcSample(600_000L, 10_000L, 50L);
        MemoryContext.GcSample post1 = new MemoryContext.GcSample(650_000L, 60_000L, 51L);
        MemoryContext.GcEvent postEvent1 = new MemoryContext.GcEvent(51L, 650_000L, 50_000L, "G1 Old Generation");
        MemoryContext first = new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                MemoryContext.PostGcHeapData.unavailable(),
                new ClassLoadingData(9000, 9500, 500),
                new RuntimeData(600_000L, 10_000L, 50L, 0, -1, MB, 4, -1, -1, null),
                pre1,
                post1,
                MemoryContext.GcEvent.unavailable(),
                postEvent1,
                MemoryContext.GcTrend.unavailable());
        // After a transient failure, Scan 3 sees lifetime gcTime = 60,000L (50,000L of which was Scan 1's histogram)
        // and still sees postEvent1 as the latest completed event.
        MemoryContext third = new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                MemoryContext.PostGcHeapData.unavailable(),
                new ClassLoadingData(9000, 9500, 500),
                new RuntimeData(660_000L, 60_000L, 51L, 0, -1, MB, 4, -1, -1, null, -1, 50_000L, "G1 Old Generation"),
                new MemoryContext.GcSample(660_000L, 60_000L, 51L),
                new MemoryContext.GcSample(660_000L, 60_000L, 51L),
                postEvent1,
                postEvent1,
                MemoryContext.GcTrend.unavailable());

        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    int step = call.incrementAndGet();
                    if (step == 1) return first;
                    if (step == 2) throw new IllegalStateException("transient failure");
                    return third;
                },
                CLOCK);

        assertThat(scanner.scan().results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-006");
        assertThat(scanner.scan().scan().status()).isEqualTo("ERROR");
        assertThat(scanner.scan().results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-006");
    }

    @Test
    void externalExplicitGcFindingsUseOnlyTheLatestSuccessfulScanWindow() {
        var uptime = new java.util.concurrent.atomic.AtomicLong(1_000L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, () -> 100L);
        MemoryScanner scanner = new MemoryScanner(
                () -> explicitGcContext(uptime.get())
                        .withLatestGcEvent(
                                new MemoryContext.GcEvent(1L, 1_000L, 500L, "G1 Old Generation", "System.gc()")),
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 0L, "end of major GC", "System.gc()", 99L, 900L);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 1_000L, 500L);
        assertThat(explicitGcFinding(scanner.scan()).severity()).isEqualTo("MEDIUM");

        uptime.set(1_100L);
        assertThat(explicitGcFinding(scanner.scan())).isNull();
        assertThat(explicitGcFinding(scanner.lastReport())).isNull();

        uptime.set(1_200L);
        tracker.recordGcNotification("G1 Old Generation", 2L, "end of major GC", "System.gc()", 1_200L, 100L);
        MemoryRuleResultDto shortPause = explicitGcFinding(scanner.scan());
        assertThat(shortPause.severity()).isEqualTo("LOW");
        assertThat(shortPause.sampleViolations().get(0))
                .contains("1 external", "100 ms")
                .doesNotContain("500 ms");

        // Equal end times and reused collector-local ids still describe distinct notifications.
        uptime.set(1_400L);
        tracker.recordGcNotification("G1 Old Generation", 3L, "end of major GC", "System.gc()", 1_300L, 100L);
        tracker.recordGcNotification("G1 Old Generation", 4L, "end of major GC", "System.gc()", 1_300L, 100L);
        tracker.recordGcNotification("PS MarkSweep", 3L, "end of major GC", "System.gc()", 1_300L, 100L);
        tracker.recordGcNotification("PS MarkSweep", 3L, "end of major GC", "System.gc()", 1_300L, 100L);
        MemoryRuleResultDto frequent = explicitGcFinding(scanner.scan());
        assertThat(frequent.severity()).isEqualTo("MEDIUM");
        assertThat(frequent.sampleViolations().get(0)).contains("3 external", "total 300 ms");

        uptime.set(1_500L);
        assertThat(explicitGcFinding(scanner.scan())).isNull();
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(6L);
        assertThat(tracker.snapshot().explicitGc().maxDurationMillis()).isEqualTo(900L);
    }

    @Test
    void failedScanDoesNotAdvanceTheExplicitGcObservationBoundary() {
        AtomicInteger call = new AtomicInteger();
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable);
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    int step = call.incrementAndGet();
                    if (step == 2) {
                        tracker.recordGcNotification(
                                "G1 Old Generation", 2L, "end of major GC", "System.gc()", 15_000L, 100L);
                        throw new IllegalStateException("transient observation failure");
                    }
                    MemoryContext.GcSample sample = new MemoryContext.GcSample(
                            step * 10_000L,
                            step >= 3 ? 8_000L : 1_500L,
                            step >= 3 ? 51L : 50L,
                            java.util.Map.of("G1 Old Generation", step >= 3 ? 2L : 1L));
                    return explicitGcContext(sample, sample);
                },
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 500L, 900L);
        assertThat(explicitGcFinding(scanner.scan()).severity()).isEqualTo("MEDIUM");
        assertThat(scanner.scan().scan().status()).isEqualTo("ERROR");
        MemoryReport recovered = scanner.scan();
        assertThat(explicitGcFinding(recovered).severity()).isEqualTo("LOW");
        assertThat(recovered.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-003", "MEM-GC-005");
        assertThat(explicitGcFinding(scanner.scan())).isNull();
    }

    @Test
    void failedCollectionPreservesTheLastSuccessfulCounterBaselineForResetAndReusedIds() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample first =
                new MemoryContext.GcSample(200_000L, 1_000L, 10L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcSample reset =
                new MemoryContext.GcSample(210_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcSample recovered =
                new MemoryContext.GcSample(230_000L, 300L, 2L, java.util.Map.of("G1 Old Generation", 2L));
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> switch (call.incrementAndGet()) {
                    case 1 -> explicitGcContext(first, first);
                    case 2 -> throw new IllegalStateException("transient collection failure");
                    case 3 -> explicitGcContext(reset, reset);
                    default -> explicitGcContext(recovered, recovered);
                },
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 100_000L, 900L);
        assertThat(explicitGcFinding(scanner.scan()).severity()).isEqualTo("MEDIUM");
        assertThat(scanner.scan().scan().status()).isEqualTo("ERROR");

        resetClock.set(210_005L);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 205_000L, 100L);
        MemoryReport newBaseline = scanner.scan();
        assertThat(explicitGcFinding(newBaseline)).isNull();
        assertThat(newBaseline.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(tracker.snapshot().explicitGc().count()).isZero();
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 205_000L, 100L);
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(1L);
        tracker.recordGcNotification("G1 Old Generation", 2L, "end of major GC", "System.gc()", 220_000L, 100L);
        MemoryReport newEpoch = scanner.scan();
        assertThat(explicitGcFinding(newEpoch).severity()).isEqualTo("LOW");
        assertThat(newEpoch.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(explicitGcFinding(newEpoch).sampleViolations().get(0)).contains("1 external", "100 ms");
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(2L);
    }

    @Test
    void unknownExplicitGcUptimeEstablishesANewBaselineWithoutReplayingHistory() {
        var uptime = new java.util.concurrent.atomic.AtomicLong(1_000L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable);
        MemoryScanner scanner = new MemoryScanner(() -> explicitGcContext(uptime.get()), CLOCK, tracker);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 500L, 900L);
        scanner.scan();
        uptime.set(-1L);
        MemoryReport unknown = scanner.scan();
        assertThat(explicitGcFinding(unknown)).isNull();
        assertThat(unknown.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        tracker.recordGcNotification("G1 Old Generation", 2L, "end of major GC", "System.gc()", 1_500L, 900L);
        uptime.set(2_000L);
        MemoryReport newBaseline = scanner.scan();
        assertThat(explicitGcFinding(newBaseline)).isNull();
        assertThat(newBaseline.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        uptime.set(3_000L);
        MemoryReport cleanWindow = scanner.scan();
        assertThat(explicitGcFinding(cleanWindow)).isNull();
        assertThat(cleanWindow.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-009:"));
    }

    @Test
    void counterResetInsideCollectionCannotCreateAComparableExplicitGcWindow() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        tracker.recordWindow(
                new MemoryContext.GcSample(800_000L, 0L, 0L, java.util.Map.of("G1 Old Generation", 0L)),
                new MemoryContext.GcSample(860_000L, 60_000L, 1L, java.util.Map.of("G1 Old Generation", 1L)),
                MemoryContext.GcEvent.unavailable(),
                MemoryContext.GcEvent.unavailable());
        MemoryContext first = explicitGcContext(
                new MemoryContext.GcSample(1_000_000L, 120_000L, 100L, java.util.Map.of("G1 Old Generation", 7L)),
                new MemoryContext.GcSample(1_050_000L, 170_000L, 101L, java.util.Map.of("G1 Old Generation", 8L)));
        MemoryContext.GcEvent histogramEvent =
                new MemoryContext.GcEvent(1L, 1_070_000L, 2_000L, "G1 Old Generation", "Heap Inspection Initiated GC");
        MemoryContext resetDuringCollection = explicitGcContext(
                new MemoryContext.GcSample(1_060_000L, 170_000L, 101L, java.util.Map.of("G1 Old Generation", 8L)),
                new MemoryContext.GcSample(1_070_000L, 2_000L, 1L, java.util.Map.of("G1 Old Generation", 1L)),
                new MemoryContext.GcEvent(8L, 1_050_000L, 50_000L, "G1 Old Generation"),
                histogramEvent);
        MemoryContext.GcSample quietSample =
                new MemoryContext.GcSample(1_075_000L, 2_000L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext afterOwnHistogram = explicitGcContext(quietSample, quietSample, histogramEvent, histogramEvent);
        MemoryContext.GcSample recoveredSample =
                new MemoryContext.GcSample(1_100_000L, 120_000L, 8L, java.util.Map.of("G1 Old Generation", 7L));
        MemoryContext.GcEvent applicationEvent =
                new MemoryContext.GcEvent(7L, 1_080_000L, 1_500L, "G1 Old Generation", "System.gc()");
        MemoryContext recovered =
                explicitGcContext(recoveredSample, recoveredSample, applicationEvent, applicationEvent);
        AtomicInteger step = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> switch (step.incrementAndGet()) {
                    case 1 -> first;
                    case 2 -> resetDuringCollection;
                    case 3 -> afterOwnHistogram;
                    default -> recovered;
                },
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 7L, "end of major GC", "System.gc()", 900_000L, 900L);
        MemoryReport initial = scanner.scan();
        assertThat(explicitGcFinding(initial)).isNotNull();
        assertThat(initial.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-002");

        resetClock.set(1_070_005L);
        MemoryReport reset = scanner.scan();
        assertThat(explicitGcFinding(reset)).isNull();
        assertThat(reset.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(reset.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-003", "MEM-GC-006");
        assertThat(reset.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-002:"));
        assertThat(reset.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-003:"));
        assertThat(reset.summary().heapUsedPercent()).isEqualTo(25);
        assertThat(tracker.snapshot().explicitGc().count()).isZero();
        assertThat(tracker.snapshot().gcTimeMillis()).isZero();
        assertThat(tracker.snapshot().gcCount()).isZero();

        MemoryReport afterHistogram = scanner.scan();
        assertThat(afterHistogram.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-006");
        tracker.recordGcNotification("G1 Old Generation", 7L, "end of major GC", "System.gc()", 1_080_000L, 1_500L);
        MemoryReport afterReset = scanner.scan();
        assertThat(explicitGcFinding(afterReset).severity()).isEqualTo("MEDIUM");
        assertThat(explicitGcFinding(afterReset).sampleViolations().get(0)).contains("1 external", "1500 ms");
        assertThat(afterReset.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(afterReset.results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-002", "MEM-GC-006");
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(1L);
        assertThat(explicitGcFinding(scanner.scan())).isNull();
    }

    @Test
    void incomparablePostHistogramMetadataStaysUnknownWithoutHidingLaterExternalPause() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(50_005L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before =
                new MemoryContext.GcSample(2_000_000L, 100_000L, 100L, java.util.Map.of("G1 Old Generation", 8L));
        MemoryContext.GcSample after =
                new MemoryContext.GcSample(50_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcEvent oldClockEvent =
                new MemoryContext.GcEvent(8L, 1_900_000L, 2_000L, "G1 Old Generation", "Heap Inspection Initiated GC");
        MemoryContext.GcSample quiet =
                new MemoryContext.GcSample(70_000L, 200L, 2L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcSample application =
                new MemoryContext.GcSample(90_000L, 2_000L, 3L, java.util.Map.of("G1 Old Generation", 3L));
        MemoryContext.GcEvent applicationEvent =
                new MemoryContext.GcEvent(3L, 80_000L, 1_500L, "G1 Old Generation", "System.gc()");
        AtomicInteger step = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> switch (step.incrementAndGet()) {
                    case 1 -> explicitGcContext(before, after, oldClockEvent, oldClockEvent);
                    case 2 -> explicitGcContext(quiet, quiet, oldClockEvent, oldClockEvent);
                    default -> explicitGcContext(application, application, applicationEvent, applicationEvent);
                },
                CLOCK,
                tracker);

        MemoryReport reset = scanner.scan();
        assertThat(reset.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-006");
        MemoryReport uncertain = scanner.scan();
        assertThat(uncertain.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-006");
        assertThat(uncertain.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-006:"));
        assertThat(scanner.scan().results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-006");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void postResetOwnHistogramIsExcludedOnQuietRescanWithoutDiagnosticDoubleCounting(boolean diagnosticOverlap) {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before =
                new MemoryContext.GcSample(800_000L, 300_000L, 100L, java.util.Map.of("G1 Old Generation", 8L));
        MemoryContext.GcSample after =
                new MemoryContext.GcSample(1_000_000L, 200_000L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcEvent own = new MemoryContext.GcEvent(
                1L,
                1_000_000L,
                diagnosticOverlap ? 200_000L : 50_000L,
                "G1 Old Generation",
                "Heap Inspection Initiated GC");
        MemoryContext.GcSample quiet =
                new MemoryContext.GcSample(1_010_000L, 200_000L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcSample application =
                new MemoryContext.GcSample(1_100_000L, 320_000L, 2L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcEvent external =
                new MemoryContext.GcEvent(2L, 1_090_000L, 1_500L, "G1 Old Generation", "System.gc()");
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    if (call.incrementAndGet() == 1) {
                        resetClock.set(1_000_005L);
                        if (diagnosticOverlap) {
                            tracker.recordWindow(
                                    before,
                                    after,
                                    MemoryContext.GcEvent.unavailable(),
                                    MemoryContext.GcEvent.unavailable());
                            tracker.recordWindow(
                                    new MemoryContext.GcSample(
                                            800_000L, 0L, 0L, java.util.Map.of("G1 Old Generation", 0L)),
                                    after,
                                    MemoryContext.GcEvent.unavailable(),
                                    own);
                        }
                        return explicitGcContext(before, after, MemoryContext.GcEvent.unavailable(), own);
                    }
                    return call.get() == 2
                            ? explicitGcContext(quiet, quiet, own, own)
                            : explicitGcContext(application, application, external, external);
                },
                CLOCK,
                tracker);

        assertThat(scanner.scan().evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-002:"));
        MemoryReport noApplicationGc = scanner.scan();
        assertThat(noApplicationGc.results())
                .extracting(MemoryRuleResultDto::id)
                .doesNotContain("MEM-GC-002", "MEM-GC-006");
        assertThat(noApplicationGc.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-002:"));
        assertThat(tracker.snapshot().gcTimeMillis()).isEqualTo(diagnosticOverlap ? 200_000L : 0L);
        assertThat(tracker.snapshot().gcCount()).isEqualTo(diagnosticOverlap ? 1L : 0L);
        MemoryReport knownApplicationGc = scanner.scan();
        assertThat(knownApplicationGc.results())
                .extracting(MemoryRuleResultDto::id)
                .contains("MEM-GC-002", "MEM-GC-006");
        assertThat(knownApplicationGc.results())
                .filteredOn(result -> result.id().equals("MEM-GC-002"))
                .singleElement()
                .satisfies(
                        result -> assertThat(result.sampleViolations().get(0)).contains("across 1 collections"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void countProofDoesNotTurnMonotonicOrPartiallyAccountedTimeIntoHealthyZero(int scenario) {
        boolean partialDiagnosticTimer = scenario == 1;
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before = new MemoryContext.GcSample(
                800_000L,
                partialDiagnosticTimer ? 300_000L : 200_000L,
                100L,
                java.util.Map.of("G1 Old Generation", 8L));
        MemoryContext.GcSample after = new MemoryContext.GcSample(
                1_000_000L, scenario == 0 ? 220_000L : 200_000L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcEvent own = new MemoryContext.GcEvent(
                1L,
                1_000_000L,
                partialDiagnosticTimer ? 150_000L : 20_000L,
                "G1 Old Generation",
                "Heap Inspection Initiated GC");
        MemoryContext.GcSample quiet = new MemoryContext.GcSample(
                1_010_000L, after.gcTimeMillis(), 1L, java.util.Map.of("G1 Old Generation", 1L));
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    if (call.incrementAndGet() == 1) {
                        resetClock.set(1_000_005L);
                        if (partialDiagnosticTimer) {
                            tracker.recordWindow(
                                    before,
                                    after,
                                    MemoryContext.GcEvent.unavailable(),
                                    MemoryContext.GcEvent.unavailable());
                            tracker.recordWindow(
                                    new MemoryContext.GcSample(
                                            800_000L, 20_000L, 0L, java.util.Map.of("G1 Old Generation", 0L)),
                                    after,
                                    MemoryContext.GcEvent.unavailable(),
                                    own);
                        }
                        return explicitGcContext(before, after, MemoryContext.GcEvent.unavailable(), own);
                    }
                    return explicitGcContext(quiet, quiet, own, own);
                },
                CLOCK,
                tracker);

        scanner.scan();
        var accounting = tracker.diagnosticAccounting(
                tracker.snapshot(), own, partialDiagnosticTimer ? after.gcTimeMillis() : -1L);
        assertThat(accounting.time()).isEqualTo(BootUiDiagnosticGcTracker.Accounting.UNKNOWN);
        assertThat(accounting.count())
                .isEqualTo(
                        partialDiagnosticTimer
                                ? BootUiDiagnosticGcTracker.Accounting.INCLUDED
                                : BootUiDiagnosticGcTracker.Accounting.NOT_INCLUDED);
        MemoryReport report = scanner.scan();
        assertThat(report.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-002");
        assertThat(report.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-002:"));
        assertThat(report.summary().heapUsedPercent()).isEqualTo(25);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void postTimerAttributionRequiresTheSameSoleRelevantCollectorInBothSamples(int shape) {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        java.util.Map<String, Long> beforeCollectors =
                switch (shape) {
                    case 0, 1 -> java.util.Map.of("G1 Old Generation", 99L, "G1 Young Generation", 1L);
                    case 3 -> null;
                    case 4 -> java.util.Map.of("PS MarkSweep", 100L);
                    case 5 -> java.util.Map.of("G1 Old Generation", 100L, "ZGC Cycles", 5L);
                    default -> java.util.Map.of("G1 Old Generation", 100L);
                };
        java.util.Map<String, Long> afterCollectors =
                switch (shape) {
                    case 0, 2 -> java.util.Map.of("G1 Old Generation", 1L, "G1 Young Generation", 0L);
                    case 3 -> java.util.Map.of();
                    case 5 -> java.util.Map.of("G1 Old Generation", 1L, "ZGC Cycles", 0L);
                    default -> java.util.Map.of("G1 Old Generation", 1L);
                };
        MemoryContext.GcSample before = new MemoryContext.GcSample(800_000L, 300_000L, 100L, beforeCollectors);
        MemoryContext.GcSample after = new MemoryContext.GcSample(1_000_000L, 220_000L, 1L, afterCollectors);
        MemoryContext.GcEvent own =
                new MemoryContext.GcEvent(1L, 1_000_000L, 20_000L, "G1 Old Generation", "Heap Inspection Initiated GC");
        MemoryContext.GcSample quiet = new MemoryContext.GcSample(1_010_000L, 220_000L, 1L, afterCollectors);
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    if (call.incrementAndGet() == 1) {
                        resetClock.set(1_000_005L);
                        return explicitGcContext(before, after, MemoryContext.GcEvent.unavailable(), own);
                    }
                    return explicitGcContext(quiet, quiet, own, own);
                },
                CLOCK,
                tracker);

        scanner.scan();
        MemoryReport report = scanner.scan();
        assertThat(report.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-002");
        if (shape == 5) {
            assertThat(report.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-002:"));
        } else {
            assertThat(report.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-002:"));
        }
        assertThat(report.summary().heapUsedPercent()).isEqualTo(25);
        if (shape < 3) {
            var accounting = tracker.diagnosticAccounting(tracker.snapshot(), own, -1L);
            assertThat(accounting.time()).isEqualTo(BootUiDiagnosticGcTracker.Accounting.UNKNOWN);
            assertThat(accounting.count()).isEqualTo(BootUiDiagnosticGcTracker.Accounting.NOT_INCLUDED);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unprovenPostResetHistogramStaysUnknownAndDoesNotHideDistinctApplicationEvents(boolean wrongId) {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before = new MemoryContext.GcSample(
                800_000L, 300_000L, 100L, java.util.Map.of("G1 Old Generation", 8L, "PS MarkSweep", 0L));
        MemoryContext.GcSample after = new MemoryContext.GcSample(
                1_000_000L, 200_000L, 1L, java.util.Map.of("G1 Old Generation", 1L, "PS MarkSweep", 0L));
        MemoryContext.GcEvent unproven = new MemoryContext.GcEvent(
                wrongId ? 2L : 1L,
                wrongId ? 1_000_000L : 2_000_000L,
                200_000L,
                "G1 Old Generation",
                "Heap Inspection Initiated GC");
        MemoryContext.GcSample quiet = new MemoryContext.GcSample(
                1_010_000L, 200_000L, 1L, java.util.Map.of("G1 Old Generation", 1L, "PS MarkSweep", 0L));
        MemoryContext.GcSample application = new MemoryContext.GcSample(
                wrongId ? 1_100_000L : 2_100_000L,
                201_500L,
                2L,
                java.util.Map.of("G1 Old Generation", wrongId ? 2L : 1L, "PS MarkSweep", wrongId ? 0L : 1L));
        MemoryContext.GcEvent external = new MemoryContext.GcEvent(
                wrongId ? 2L : 1L,
                wrongId ? 1_090_000L : 2_000_000L,
                1_500L,
                wrongId ? "G1 Old Generation" : "PS MarkSweep",
                "System.gc()");
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    if (call.incrementAndGet() == 1) {
                        resetClock.set(1_000_005L);
                        return explicitGcContext(before, after, MemoryContext.GcEvent.unavailable(), unproven);
                    }
                    return call.get() == 2
                            ? explicitGcContext(quiet, quiet, unproven, unproven)
                            : explicitGcContext(application, application, external, external);
                },
                CLOCK,
                tracker);

        scanner.scan();
        MemoryReport uncertain = scanner.scan();
        assertThat(uncertain.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-002", "MEM-GC-006");
        assertThat(uncertain.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-002:"));
        assertThat(uncertain.summary().heapUsedPercent()).isEqualTo(25);
        assertThat(scanner.scan().results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-006");
    }

    @Test
    void betweenScanResetRetainsPossibleOwnIdentityWithoutOldClockWatermark() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before =
                new MemoryContext.GcSample(900_000L, 10_000L, 10L, java.util.Map.of("G1 Old Generation", 7L));
        MemoryContext.GcSample ownSample =
                new MemoryContext.GcSample(1_000_000L, 12_000L, 11L, java.util.Map.of("G1 Old Generation", 8L));
        MemoryContext.GcEvent own =
                new MemoryContext.GcEvent(8L, 1_000_000L, 2_000L, "G1 Old Generation", "Heap Inspection Initiated GC");
        MemoryContext.GcSample reset =
                new MemoryContext.GcSample(50_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcSample quiet =
                new MemoryContext.GcSample(70_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcSample application =
                new MemoryContext.GcSample(90_000L, 1_600L, 8L, java.util.Map.of("G1 Old Generation", 8L));
        MemoryContext.GcEvent external =
                new MemoryContext.GcEvent(8L, 80_000L, 1_500L, "G1 Old Generation", "System.gc()");
        AtomicInteger call = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> switch (call.incrementAndGet()) {
                    case 1 -> explicitGcContext(before, ownSample, MemoryContext.GcEvent.unavailable(), own);
                    case 2 -> explicitGcContext(reset, reset, own, own);
                    case 3 -> explicitGcContext(quiet, quiet, own, own);
                    default -> explicitGcContext(application, application, external, external);
                },
                CLOCK,
                tracker);
        scanner.scan();
        resetClock.set(50_005L);
        MemoryReport newBaseline = scanner.scan();
        assertThat(newBaseline.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-006");
        assertThat(newBaseline.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-006:"));
        assertThat(scanner.scan().results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-006");
        assertThat(scanner.scan().results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-006");
    }

    @Test
    void counterResetRecordedBeforeCollectionPreservesTheNewEpochNotification() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample first =
                new MemoryContext.GcSample(200_000L, 1_000L, 10L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcSample reset =
                new MemoryContext.GcSample(50_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 0L));
        MemoryContext.GcSample afterReset =
                new MemoryContext.GcSample(70_000L, 200L, 2L, java.util.Map.of("G1 Old Generation", 1L));
        MemoryContext.GcSample recovered =
                new MemoryContext.GcSample(80_000L, 300L, 3L, java.util.Map.of("G1 Old Generation", 2L));
        AtomicInteger step = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> switch (step.incrementAndGet()) {
                    case 1 -> explicitGcContext(first, first);
                    case 2 -> explicitGcContext(afterReset, afterReset);
                    default -> explicitGcContext(recovered, recovered);
                },
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 100_000L, 900L);
        scanner.scan();
        resetClock.set(50_005L);
        tracker.recordWindow(first, reset, MemoryContext.GcEvent.unavailable(), MemoryContext.GcEvent.unavailable());
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 60_000L, 100L);
        long newEpoch = tracker.snapshot().epoch();

        MemoryReport newBaseline = scanner.scan();
        assertThat(explicitGcFinding(newBaseline)).isNull();
        assertThat(newBaseline.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(tracker.snapshot().epoch()).isEqualTo(newEpoch);
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(1L);
        tracker.recordGcNotification("G1 Old Generation", 2L, "end of major GC", "System.gc()", 75_000L, 100L);
        MemoryReport newActivity = scanner.scan();
        assertThat(explicitGcFinding(newActivity).severity()).isEqualTo("LOW");
        assertThat(explicitGcFinding(newActivity).sampleViolations().get(0)).contains("1 external", "100 ms");
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(2L);
        assertThat(tracker.snapshot().epoch()).isEqualTo(newEpoch);
    }

    @Test
    void counterResetAlreadyRecordedDuringCollectionPreservesTheNewEpochNotification() {
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample before =
                new MemoryContext.GcSample(1_000L, 100L, 50L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcSample after =
                new MemoryContext.GcSample(2_000L, 10L, 1L, java.util.Map.of("G1 Old Generation", 0L));
        MemoryContext.GcSample recovered =
                new MemoryContext.GcSample(2_100L, 100L, 2L, java.util.Map.of("G1 Old Generation", 1L));
        AtomicInteger step = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    if (step.incrementAndGet() == 1) {
                        resetClock.set(2_005L);
                        tracker.recordWindow(
                                before,
                                after,
                                MemoryContext.GcEvent.unavailable(),
                                MemoryContext.GcEvent.unavailable());
                        tracker.recordGcNotification(
                                "G1 Old Generation", 1L, "end of major GC", "System.gc()", 2_010L, 100L);
                        return explicitGcContext(before, after);
                    }
                    return explicitGcContext(recovered, recovered);
                },
                CLOCK,
                tracker);
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 900L, 900L);

        MemoryReport reset = scanner.scan();
        assertThat(explicitGcFinding(reset)).isNull();
        assertThat(reset.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));
        long newEpoch = tracker.snapshot().epoch();
        assertThat(tracker.snapshot().explicitGc().count()).isEqualTo(1L);
        MemoryReport afterReset = scanner.scan();
        assertThat(explicitGcFinding(afterReset).severity()).isEqualTo("LOW");
        assertThat(afterReset.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-009:"));
        assertThat(tracker.snapshot().epoch()).isEqualTo(newEpoch);
    }

    @Test
    void counterDiscontinuityBetweenScansResetsTrackerExplicitGcObservationsAndAcceptsReusedIds() {
        AtomicInteger step = new AtomicInteger();
        var resetClock = new java.util.concurrent.atomic.AtomicLong(0L);
        BootUiDiagnosticGcTracker tracker =
                new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable, true, resetClock::get);
        MemoryContext.GcSample firstSample =
                new MemoryContext.GcSample(200_000L, 1_000L, 10L, java.util.Map.of("G1 Old Generation", 2L));
        MemoryContext.GcSample resetSample =
                new MemoryContext.GcSample(50_000L, 100L, 1L, java.util.Map.of("G1 Old Generation", 0L));
        MemoryContext first = new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                MemoryContext.PostGcHeapData.unavailable(),
                new ClassLoadingData(9000, 9500, 500),
                healthyRuntime(),
                firstSample,
                firstSample,
                MemoryContext.GcEvent.unavailable(),
                MemoryContext.GcEvent.unavailable(),
                MemoryContext.GcTrend.unavailable());
        MemoryContext afterReset = new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                MemoryContext.PostGcHeapData.unavailable(),
                new ClassLoadingData(9000, 9500, 500),
                healthyRuntime(),
                resetSample,
                resetSample,
                MemoryContext.GcEvent.unavailable(),
                MemoryContext.GcEvent.unavailable(),
                MemoryContext.GcTrend.unavailable());

        MemoryScanner scanner = new MemoryScanner(
                () -> switch (step.incrementAndGet()) {
                    case 1 -> first;
                    case 2 -> afterReset;
                    default -> explicitGcContext(70_000L);
                },
                CLOCK,
                tracker);

        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 100_000L, 250L);
        assertThat(scanner.scan().results()).extracting(MemoryRuleResultDto::id).contains("MEM-GC-009");

        // Counter discontinuity on Scan 2 clears pre-reset explicit GCs
        resetClock.set(50_005L);
        MemoryReport reset = scanner.scan();
        assertThat(reset.results()).extracting(MemoryRuleResultDto::id).doesNotContain("MEM-GC-009");
        assertThat(reset.evidence().limitations()).anyMatch(note -> note.startsWith("MEM-GC-009:"));

        // Reused gcId=1 in the new epoch is accepted again
        tracker.recordGcNotification("G1 Old Generation", 1L, "end of major GC", "System.gc()", 60_000L, 300L);
        MemoryReport recoveredReport = scanner.scan();
        assertThat(recoveredReport.results())
                .extracting(MemoryRuleResultDto::id)
                .contains("MEM-GC-009");
        assertThat(recoveredReport.evidence().limitations()).noneMatch(note -> note.startsWith("MEM-GC-009:"));
    }

    private static MemoryContext explicitGcContext(MemoryContext.GcSample pre, MemoryContext.GcSample post) {
        return explicitGcContext(pre, post, MemoryContext.GcEvent.unavailable(), MemoryContext.GcEvent.unavailable());
    }

    private static MemoryContext explicitGcContext(
            MemoryContext.GcSample pre,
            MemoryContext.GcSample post,
            MemoryContext.GcEvent latestEvent,
            MemoryContext.GcEvent postEvent) {
        return new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                MemoryContext.PostGcHeapData.unavailable(),
                ClassLoadingData.empty(),
                new RuntimeData(pre.uptimeMillis(), pre.gcTimeMillis(), pre.gcCount(), 0, -1, MB, 4, -1, -1, null),
                pre,
                post,
                latestEvent,
                postEvent,
                MemoryContext.GcTrend.unavailable());
    }

    private static MemoryContext explicitGcContext(long uptime) {
        return new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                ClassLoadingData.empty(),
                new RuntimeData(uptime, 1_500L, 50L, 0, -1, MB, 4, -1, -1, null));
    }

    private static MemoryRuleResultDto explicitGcFinding(MemoryReport report) {
        return report.results().stream()
                .filter(result -> result.id().equals("MEM-GC-009"))
                .findFirst()
                .orElse(null);
    }

    private static MemoryRuleResultDto result(String id, String status) {
        return new MemoryRuleResultDto(
                id,
                "name",
                "Category",
                "HIGH",
                "description",
                status,
                0,
                List.of("detail"),
                "recommendation",
                "https://example.com");
    }
}
