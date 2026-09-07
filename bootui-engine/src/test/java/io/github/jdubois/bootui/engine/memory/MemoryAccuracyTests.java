package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.engine.memory.MemoryContext.BufferPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcSample;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcTrend;
import io.github.jdubois.bootui.engine.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.PostGcHeapData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ThreadData;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryAccuracyTests {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void stableRuleIdsRemainExactlyTheSame() {
        assertThat(MemoryRuleRegistry.activeRules().stream()
                        .map(rule -> rule.definition().id()))
                .containsExactlyInAnyOrder(
                        "MEM-HEAP-001",
                        "MEM-HEAP-002",
                        "MEM-HEAP-003",
                        "MEM-HEAP-004",
                        "MEM-HEAP-005",
                        "MEM-HEAP-006",
                        "MEM-HEAP-007",
                        "MEM-HEAP-008",
                        "MEM-FOOTPRINT-001",
                        "MEM-FOOTPRINT-002",
                        "MEM-FOOTPRINT-003",
                        "MEM-FOOTPRINT-004",
                        "MEM-POOL-001",
                        "MEM-POOL-002",
                        "MEM-POOL-003",
                        "MEM-POOL-004",
                        "MEM-POOL-005",
                        "MEM-POOL-006",
                        "MEM-POOL-007",
                        "MEM-GC-001",
                        "MEM-GC-002",
                        "MEM-GC-003",
                        "MEM-GC-004",
                        "MEM-GC-005",
                        "MEM-GC-006",
                        "MEM-GC-007",
                        "MEM-THREAD-001",
                        "MEM-THREAD-002",
                        "MEM-THREAD-003",
                        "MEM-THREAD-004",
                        "MEM-CONTENT-001",
                        "MEM-CONTENT-002",
                        "MEM-CONTENT-003",
                        "MEM-CONTENT-004",
                        "MEM-CLASS-001",
                        "MEM-CLASS-002");
    }

    @Test
    void arithmeticCannotWrapIntoHealthyMeasurements() {
        assertThat(MemoryFormat.percentOf(Long.MAX_VALUE - 1, Long.MAX_VALUE)).isEqualTo(99);
        assertThat(MemoryFormat.percentOf(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(100);
        assertThat(MemoryFormat.percentOf(94, 100)).isEqualTo(94);
        assertThat(MemoryFormat.percentOf(95, 100)).isEqualTo(95);
        assertThat(MemoryFormat.percentOf(-1, 100)).isZero();
        assertThat(MemoryFormat.percentOf(10, 0)).isZero();
        assertThat(MemoryFormat.sum(Long.MAX_VALUE, 1)).isEqualTo(-1);
        assertThat(MemoryFormat.sum(10, -1)).isEqualTo(-1);
        assertThat(MemoryFormat.sum(Long.MAX_VALUE - 1, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(MemoryFormat.product(Long.MAX_VALUE, 2)).isEqualTo(-1);
        assertThat(MemoryFormat.product(0, 2)).isZero();
        assertThat(MemoryCollector.parseMemorySize("9223372036854775807t")).isEqualTo(-1);
    }

    @Test
    void undefinedMaximaNeverUseCommittedAsACapIncludingGenerationalZgc() {
        MemoryPoolSnapshot old = new MemoryPoolSnapshot("ZGC Old Generation", 100, 100, -1);
        assertThat(old.usedPercent()).isZero();
        MemoryContext context =
                context(memory(100, 100, -1, List.of(old), List.of(), null), PostGcHeapData.unavailable());
        assertThat(context.heapUsedPercent()).isZero();
        assertThat(new HighHeapUtilizationRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(new OldGenerationNearMaxRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(new SmallMaxHeapUnderPressureRule().evaluate(context).status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void collectorSpecificOldPoolsUseOnlyReportedMaxima() {
        for (String name :
                List.of("G1 Old Gen", "PS Old Gen", "Tenured Gen", "ZGC Old Generation", "Shenandoah Old Gen")) {
            MemoryPoolSnapshot old = new MemoryPoolSnapshot(name, 90, 90, 100);
            MemoryContext context =
                    context(memory(90, 90, 100, List.of(old), List.of(), null), PostGcHeapData.unavailable());
            assertThat(new OldGenerationNearMaxRule().evaluate(context).status())
                    .as(name)
                    .isEqualTo("VIOLATION");
        }
        for (String name : List.of("ZHeap", "Shenandoah", "G1 Eden Space")) {
            MemoryContext context = context(
                    memory(90, 90, 100, List.of(new MemoryPoolSnapshot(name, 90, 90, 100)), List.of(), null),
                    PostGcHeapData.unavailable());
            assertThat(new OldGenerationNearMaxRule().evaluate(context).status())
                    .as(name)
                    .isEqualTo("SKIPPED");
        }
    }

    @Test
    void unsegmentedCodeCacheIsRecognizedAndUndefinedCapsSkip() {
        MemoryContext known = context(
                memory(10, 100, 100, List.of(new MemoryPoolSnapshot("CodeCache", 95, 100, 100)), List.of(), null),
                PostGcHeapData.unavailable());
        assertThat(new CodeCacheSaturationRule().evaluate(known).status()).isEqualTo("VIOLATION");
        MemoryContext unknown = context(
                memory(10, 100, 100, List.of(new MemoryPoolSnapshot("CodeCache", 95, 100, -1)), List.of(), null),
                PostGcHeapData.unavailable());
        assertThat(new CodeCacheSaturationRule().evaluate(unknown).status()).isEqualTo("SKIPPED");
    }

    @Test
    void bufferUnknownIsPreservedAndEachAttributeIsReadOnce() {
        BufferPoolMXBean pool = mock(BufferPoolMXBean.class);
        when(pool.getName()).thenReturn("direct");
        when(pool.getMemoryUsed()).thenReturn(-1L);
        when(pool.getTotalCapacity()).thenReturn(42L);
        when(pool.getCount()).thenReturn(1L);
        assertThat(MemoryCollector.bufferPoolSnapshot(pool)).isEqualTo(new BufferPoolSnapshot("direct", -1, 42, 1));
        verify(pool, times(1)).getMemoryUsed();
        verify(pool, times(1)).getTotalCapacity();
        verify(pool, times(1)).getCount();
    }

    @Test
    void directAggregationIsIndependentOfPoolOrderAndPreservesMissingMetrics() {
        BufferPoolSnapshot mapped = new BufferPoolSnapshot("mapped", 100, 100, 2);
        BufferPoolSnapshot direct = new BufferPoolSnapshot("direct", 42, 40, 1);
        assertThat(MemoryCollector.directBufferPoolSnapshot(List.of(mapped, direct)))
                .isEqualTo(direct);
        assertThat(MemoryCollector.directBufferPoolSnapshot(List.of(direct, mapped)))
                .isEqualTo(direct);
        assertThat(MemoryCollector.directBufferPoolSnapshot(List.of(mapped)))
                .isEqualTo(new BufferPoolSnapshot("direct", -1, -1, -1));
        assertThat(MemoryCollector.directBufferPoolSnapshot(List.of()))
                .isEqualTo(new BufferPoolSnapshot("direct", -1, -1, -1));
        assertThat(MemoryCollector.directBufferPoolSnapshot(
                        List.of(mapped, direct, new BufferPoolSnapshot("direct", -1, 10, 1))))
                .isEqualTo(new BufferPoolSnapshot("direct", -1, 50, 2));
    }

    @Test
    void directCapRequiresKnownOptionRatherThanGuessingFromHeap() {
        assertThat(MemoryCollector.effectiveMaxDirectMemory(List.of(), GB, "0")).isEqualTo(GB);
        assertThat(MemoryCollector.effectiveMaxDirectMemory(List.of(), GB, null))
                .isEqualTo(-1);
        assertThat(MemoryCollector.effectiveMaxDirectMemory(
                        List.of("-XX:MaxDirectMemorySize=10m", "-XX:MaxDirectMemorySize=20m"), GB, null))
                .isEqualTo(20 * 1024 * 1024);
        assertThat(MemoryCollector.effectiveMaxDirectMemory(
                        List.of("-XX:MaxDirectMemorySize=10m", "-XX:MaxDirectMemorySize=0"), GB, null))
                .isEqualTo(-1);
        assertThat(new DirectBufferGrowthRule()
                        .evaluate(
                                context(memory(10, 100, 100, List.of(), List.of(), null), PostGcHeapData.unavailable()))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void partialUnknownGcMetricsAreNotCompleteSums() {
        GcSample timeUnknown = MemoryCollector.gcSample(
                100, List.of(gc("G1 Young Generation", 2, 10), gc("G1 Old Generation", 0, -1)));
        assertThat(timeUnknown.gcTimeMillis()).isEqualTo(-1);
        assertThat(timeUnknown.gcCount()).isEqualTo(2);
        assertThat(timeUnknown.perCollectorCounts()).containsEntry("G1 Old Generation", 0L);
        GcSample countUnknown = MemoryCollector.gcSample(100, List.of(gc("Copy", -1, 10)));
        assertThat(countUnknown.gcCount()).isEqualTo(-1);
        assertThat(countUnknown.gcTimeMillis()).isEqualTo(10);
        assertThat(MemoryCollector.gcSample(100, List.of()).gcTimeMillis()).isEqualTo(-1);
        assertThat(MemoryCollector.gcSample(
                                100, List.of(gc("Copy", Long.MAX_VALUE, Long.MAX_VALUE), gc("MarkSweepCompact", 1, 1)))
                        .gcTimeMillis())
                .isEqualTo(-1);
    }

    @Test
    void concurrentCycleTimersAreNotAddedToPauseTimers() {
        for (String cycle : List.of("ZGC Cycles", "ZGC Major Cycles", "ZGC Minor Cycles", "Shenandoah Cycles")) {
            GcSample sample = MemoryCollector.gcSample(100, List.of(gc(cycle, 5, 90), gc("ZGC Pauses", 10, 3)));
            assertThat(sample.gcTimeMillis()).as(cycle).isEqualTo(3);
            assertThat(sample.gcCount()).isEqualTo(10);
        }
        assertThat(MemoryCollector.gcSample(100, List.of(gc("G1 Concurrent GC", 1, 3)))
                        .gcTimeMillis())
                .isEqualTo(3);
    }

    @Test
    void gcDiscontinuitiesInvalidateRatherThanClampToZero() {
        GcSample baseline = new GcSample(100, 10, 5, Map.of("G1 Old Generation", 5L));
        for (GcSample current : List.of(
                new GcSample(100, 11, 6, baseline.perCollectorCounts()),
                new GcSample(90, 11, 6, baseline.perCollectorCounts()),
                new GcSample(200, 9, 6, baseline.perCollectorCounts()),
                new GcSample(200, 11, 4, baseline.perCollectorCounts()),
                new GcSample(200, 11, 6, Map.of("G1 Old Generation", 4L)),
                new GcSample(200, 11, 6, Map.of()),
                new GcSample(200, 11, 6, Map.of("G1 Old Generation", 5L, "new collector", 0L)))) {
            assertThat(GcTrend.between(baseline, current).available())
                    .as(current.toString())
                    .isFalse();
        }
        GcTrend zeroTime = GcTrend.between(baseline, new GcSample(200, 10, 6, Map.of("G1 Old Generation", 6L)));
        assertThat(zeroTime.available()).isTrue();
        assertThat(zeroTime.deltaGcTimeMillis()).isZero();
        assertThat(zeroTime.deltaGcCount()).isEqualTo(1);
    }

    @Test
    void independentCounterAvailabilityDoesNotInventZeros() {
        GcTrend trend = GcTrend.between(
                new GcSample(10, -1, 1, Map.of("G1 Old Generation", 1L)),
                new GcSample(20, -1, 2, Map.of("G1 Old Generation", 2L)));
        assertThat(trend.deltaGcTimeMillis()).isEqualTo(-1);
        assertThat(trend.perCollectorDeltas()).containsEntry("G1 Old Generation", 1L);
        assertThat(new G1FullGcFrequencyRule()
                        .evaluate(context(null, null).withGcTrend(trend))
                        .severity())
                .isEqualTo("INFO");
        assertThat(new RecentGcOverheadRule()
                        .evaluate(context(null, null).withGcTrend(trend))
                        .status())
                .isEqualTo("SKIPPED");
        GcTrend unknownCount = GcTrend.between(new GcSample(10, 1, -1), new GcSample(20, 2, -1));
        assertThat(unknownCount.deltaGcTimeMillis()).isEqualTo(1);
        assertThat(unknownCount.deltaGcCount()).isEqualTo(-1);
    }

    @Test
    void bufferUnknownGapAndPoolAbsenceRestartConsecutiveEvidence() {
        for (MemoryContext gap : List.of(buffer(-1), context(null, null))) {
            MemoryScanner scanner =
                    scanner(buffer(1), buffer(2), buffer(3), gap, buffer(4), buffer(5), buffer(6), buffer(7));
            for (int i = 0; i < 7; i++) assertAbsent(scanner.scan(), "MEM-POOL-007");
            assertThat(ids(scanner.scan())).contains("MEM-POOL-007");
        }
    }

    @Test
    void oldGenerationUnknownGapAndInvalidUsageRestartConsecutiveEvidence() {
        for (MemoryContext gap : List.of(context(null, null), old(-1))) {
            MemoryScanner scanner = scanner(old(1), old(2), old(3), gap, old(4), old(5), old(6), old(7));
            for (int i = 0; i < 7; i++) assertAbsent(scanner.scan(), "MEM-HEAP-008");
            assertThat(ids(scanner.scan())).contains("MEM-HEAP-008");
        }
    }

    @Test
    void failedScansBreakTrendsWithoutDiscardingLastReportContract() {
        AtomicInteger index = new AtomicInteger();
        MemoryScanner scanner = new MemoryScanner(
                () -> {
                    int next = index.incrementAndGet();
                    if (next == 4) throw new IllegalStateException("failed");
                    return old(next);
                },
                Clock.systemUTC());
        for (int i = 0; i < 3; i++) assertAbsent(scanner.scan(), "MEM-HEAP-008");
        assertThat(scanner.scan().scan().status()).isEqualTo("ERROR");
        for (int i = 0; i < 3; i++) assertAbsent(scanner.scan(), "MEM-HEAP-008");
        assertThat(ids(scanner.scan())).contains("MEM-HEAP-008");
    }

    @Test
    void collectorFailuresArePartialWithoutHidingValidFindings() {
        MemoryCollector collector = new MemoryCollector(
                () -> {
                    throw new IllegalStateException("supplier detail");
                },
                () -> {
                    throw new IllegalStateException("supplier detail");
                },
                ContainerMemoryLimitDetector.disabled());
        MemoryContext failed = collector.collect();
        assertThat(failed.threads().collectionError()).isNotBlank();
        assertThat(failed.heapContent().collectionError()).isNotBlank();
        MemoryContext evidence = new MemoryContext(
                memory(99, 100, 100, List.of(), List.of(), null),
                failed.threads(),
                failed.heapContent(),
                ClassLoadingData.empty(),
                RuntimeData.empty());
        MemoryScanner scanner = new MemoryScanner(() -> evidence, Clock.systemUTC());
        MemoryReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(ids(report)).contains("MEM-HEAP-001");
        assertThat(report.analysisErrors())
                .extracting(MemoryRuleResultDto::id)
                .contains("MEM-THREAD-001", "MEM-CONTENT-001");
        assertThat(report.analysisErrors().toString()).doesNotContain("supplier detail");
        MemoryReport dismissed = scanner.applyDismissals(report, Set.of("MEM-HEAP-001"));
        assertThat(dismissed.analysisErrors()).isEqualTo(report.analysisErrors());
        assertThat(dismissed.scan().status()).isEqualTo("PARTIAL");
        assertThat(dismissed.violationsFound()).isZero();
    }

    @Test
    void unavailableThreadReportIsNotAPassingDeadlockCheck() {
        MemoryContext evidence = new MemoryCollector(
                        () -> ThreadDumpReport.unavailable("not supported"),
                        () -> null,
                        ContainerMemoryLimitDetector.disabled())
                .collect();
        assertThat(new DeadlockDetectedRule().evaluate(evidence).status()).isEqualTo("ERROR");
        assertThat(new DeadlockDetectedRule().evaluate(context(null, null)).status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void unsupportedHistogramIsSkippedButMalformedOrFailingHistogramIsAnError() {
        MemoryContext unsupported = new MemoryCollector(
                        () -> null,
                        () -> {
                            throw new javax.management.InstanceNotFoundException();
                        },
                        ContainerMemoryLimitDetector.disabled())
                .collect();
        assertThat(new BigObjectsRule().evaluate(unsupported).status()).isEqualTo("SKIPPED");
        MemoryContext malformed = new MemoryCollector(
                        () -> null, () -> "histogram not generated", ContainerMemoryLimitDetector.disabled())
                .collect();
        assertThat(new BigObjectsRule().evaluate(malformed).status()).isEqualTo("ERROR");
    }

    @Test
    void histogramSuccessIsOnlyPostDiagnosticOccupancy() {
        MemoryContext evidence = new MemoryCollector(
                        () -> null, () -> "1: 100 6400 java.lang.Object", ContainerMemoryLimitDetector.disabled())
                .collect();
        assertThat(evidence.heapContent().available()).isTrue();
        assertThat(evidence.postGcHeap().heapCommitted())
                .isGreaterThanOrEqualTo(evidence.postGcHeap().heapUsed());
        assertThat(new HighHeapUtilizationRule().definition().description())
                .contains("does not verify a completed full GC");
    }

    @Test
    void committedSlackNeverMixesSnapshotsOrClaimsSafeDownsizing() {
        MemoryData before = memory(7 * GB, 8 * GB, 10 * GB, List.of(), List.of(), null);
        MemoryContext afterShrink = context(before, new PostGcHeapData(true, GB, false, -1, GB));
        assertThat(new OverProvisionedHeapRule().evaluate(afterShrink).status()).isEqualTo("PASS");
        MemoryContext unavailablePostCommitted = context(before, new PostGcHeapData(true, GB, false, -1));
        assertThat(new OverProvisionedHeapRule()
                        .evaluate(unavailablePostCommitted)
                        .status())
                .isEqualTo("PASS");
        MemoryContext slack = context(before, new PostGcHeapData(true, GB, false, -1, 4 * GB));
        assertThat(new OverProvisionedHeapRule().evaluate(slack).severity()).isEqualTo("INFO");
        assertThat(new OverProvisionedHeapRule()
                        .evaluate(slack)
                        .sampleViolations()
                        .get(0))
                .contains("one snapshot");
    }

    @Test
    void zeroUsageAndIncoherentSwapAreNotInventedPressure() {
        MemoryContext zero = context(memory(0, 0, 100, List.of(), List.of(), 100L), null);
        assertThat(new ContainerMemoryPressureRule().evaluate(zero).status()).isEqualTo("PASS");
        RuntimeData invalid = new RuntimeData(700_000, 0, 0, 0, -1, 1024, 4, 200, 100, null);
        MemoryContext context = new MemoryContext(null, null, null, null, invalid);
        assertThat(new HighSwapUtilizationRule().evaluate(context).status()).isEqualTo("SKIPPED");
    }

    @Test
    void deliberateCompilerAndContainerReenablingDoNotUseEarlierFlags() {
        MemoryData memory = memory(10, 100, 100, List.of(), List.of(), 200L);
        MemoryData flags = new MemoryData(
                10,
                100,
                100,
                0,
                0,
                -1,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of(
                        "-Xint",
                        "-Xmixed",
                        "-XX:-UseCompiler",
                        "-XX:+UseCompiler",
                        "-XX:TieredStopAtLevel=1",
                        "-XX:TieredStopAtLevel=4",
                        "-XX:-UseContainerSupport",
                        "-XX:+UseContainerSupport"),
                memory.gcCollectorNames(),
                200L,
                0L);
        assertThat(new InterpretedJitModeRule().evaluate(context(flags, null)).status())
                .isEqualTo("PASS");
        assertThat(new ContainerSupportDisabledRule()
                        .evaluate(context(flags, null))
                        .status())
                .isEqualTo("PASS");
    }

    private static GarbageCollectorMXBean gc(String name, long count, long time) {
        GarbageCollectorMXBean bean = mock(GarbageCollectorMXBean.class);
        when(bean.getName()).thenReturn(name);
        when(bean.getCollectionCount()).thenReturn(count);
        when(bean.getCollectionTime()).thenReturn(time);
        return bean;
    }

    private static MemoryData memory(
            long used,
            long committed,
            long max,
            List<MemoryPoolSnapshot> pools,
            List<BufferPoolSnapshot> buffers,
            Long limit) {
        return new MemoryData(
                used,
                committed,
                max,
                0,
                0,
                -1,
                pools,
                0,
                0,
                0,
                -1,
                List.of(),
                List.of("G1 Young Generation"),
                limit,
                0L,
                buffers);
    }

    private static MemoryContext context(MemoryData memory, PostGcHeapData post) {
        return new MemoryContext(
                memory,
                ThreadData.empty(),
                HeapContentData.unavailable(),
                post,
                ClassLoadingData.empty(),
                new RuntimeData(700_000, 0, 0, 0, -1, 1024, 4, -1, -1, null),
                null,
                GcTrend.unavailable());
    }

    private static MemoryContext buffer(long used) {
        return context(
                memory(10, 100, 100, List.of(), List.of(new BufferPoolSnapshot("direct", used, 100, 10)), null), null);
    }

    private static MemoryContext old(long used) {
        return context(null, new PostGcHeapData(true, used, true, used));
    }

    private static MemoryScanner scanner(MemoryContext... contexts) {
        AtomicInteger index = new AtomicInteger();
        return new MemoryScanner(() -> contexts[index.getAndIncrement()], Clock.systemUTC());
    }

    private static List<String> ids(MemoryReport report) {
        return report.results().stream().map(MemoryRuleResultDto::id).toList();
    }

    private static void assertAbsent(MemoryReport report, String... ids) {
        assertThat(ids(report)).doesNotContainAnyElementsOf(Arrays.asList(ids));
    }
}
