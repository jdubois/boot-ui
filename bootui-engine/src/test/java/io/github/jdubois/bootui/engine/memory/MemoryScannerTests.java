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
