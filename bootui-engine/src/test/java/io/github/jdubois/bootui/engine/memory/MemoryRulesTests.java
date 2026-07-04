package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcSample;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcTrend;
import io.github.jdubois.bootui.engine.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.PostGcHeapData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ThreadData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Focused unit tests for the rules reworked or added in the Memory Advisor hardening phase. */
class MemoryRulesTests {

    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-06T08:00:00Z"), ZoneOffset.UTC);

    // --- MEM-HEAP-001: post-GC dual snapshot -------------------------------------------------

    @Test
    void heapUtilizationStillHighAfterGcIsReportedHigh() {
        MemoryData memory = memory(500 * MB, 1 * GB, List.of(), null, List.of("-Xmx1g"));
        MemoryContext context =
                context(memory, ThreadData.empty(), new PostGcHeapData(true, 980 * MB, false, -1), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-HEAP-001");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations().get(0)).contains("after a full GC");
    }

    @Test
    void heapUtilizationHighInSingleSnapshotWithoutPostGcIsReportedMedium() {
        MemoryData memory = memory(980 * MB, 1 * GB, List.of(), null, List.of("-Xmx1g"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-HEAP-001");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void heapUtilizationThatDropsAfterGcIsNotReported() {
        MemoryData memory = memory(980 * MB, 1 * GB, List.of(), null, List.of("-Xmx1g"));
        MemoryContext context =
                context(memory, ThreadData.empty(), new PostGcHeapData(true, 300 * MB, false, -1), healthyRuntime());

        assertThat(find(scan(context), "MEM-HEAP-001")).isNull();
    }

    // --- MEM-HEAP-002: post-GC old generation ------------------------------------------------

    @Test
    void oldGenerationStillFullAfterGcIsFlagged() {
        MemoryPoolSnapshot oldGen = new MemoryPoolSnapshot("G1 Old Gen", 200 * MB, 512 * MB, 1 * GB);
        MemoryData memory = memory(600 * MB, 2 * GB, List.of(oldGen), null, List.of("-Xmx2g"));
        MemoryContext context = context(
                memory, ThreadData.empty(), new PostGcHeapData(true, 600 * MB, true, 900 * MB), healthyRuntime());

        assertThat(find(scan(context), "MEM-HEAP-002")).isNotNull();
    }

    @Test
    void oldGenerationThatDropsAfterGcIsNotReportedEvenWhenPreGcWasHigh() {
        MemoryPoolSnapshot oldGen = new MemoryPoolSnapshot("G1 Old Gen", 970 * MB, 990 * MB, 1 * GB);
        MemoryData memory = memory(600 * MB, 2 * GB, List.of(oldGen), null, List.of("-Xmx2g"));
        MemoryContext context = context(
                memory, ThreadData.empty(), new PostGcHeapData(true, 600 * MB, true, 100 * MB), healthyRuntime());

        assertThat(find(scan(context), "MEM-HEAP-002")).isNull();
    }

    // --- MEM-HEAP-003: dead unbounded-heap branch removed ------------------------------------

    @Test
    void heapMaxNotReportedIsSkippedNotFlagged() {
        MemoryData memory = memory(500 * MB, 0, List.of(), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-HEAP-003");

        // Should be skipped, not a violation
        assertThat(result).isNull();
    }

    // --- MEM-HEAP-004: compressed-oops with ground-truth VM option ---------------------------

    @Test
    void compressedOopsCliffIsSkippedForZgc() {
        MemoryData memory = memory(10 * GB, 40 * GB, List.of(), null, List.of("-Xmx40g"), List.of("ZGC Cycles"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-HEAP-004")).isNull();
    }

    @Test
    void compressedOopsCliffSkippedWhenVmOptionReturnsFalse() {
        MemoryData memory =
                memory(10 * GB, 40 * GB, List.of(), null, List.of("-Xmx40g"), List.of("G1 Young Generation"));
        RuntimeData runtime = runtimeWithCompressedOops(Boolean.FALSE);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-HEAP-004")).isNull();
    }

    @Test
    void compressedOopsBoundaryShiftsWithObjectAlignment() {
        MemoryData memory = memory(
                10 * GB,
                70 * GB,
                List.of(),
                null,
                List.of("-Xmx70g", "-XX:ObjectAlignmentInBytes=16"),
                List.of("G1 Young Generation"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-HEAP-004");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("object alignment 16 bytes");
    }

    // --- MEM-FOOTPRINT-002: platform thread stack reservation --------------------------------

    @Test
    void largeAbsoluteStackReservationWithoutContainerIsMedium() {
        ThreadData threads = threads(1100);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void stackReservationLargeRelativeToContainerWithoutCurrentUsageIsMedium() {
        // Container current-usage is unknown (containerMemoryCurrentBytes not supplied), so the rule
        // cannot confirm resident risk and must not escalate to HIGH from the ratio breach alone.
        ThreadData threads = threads(300);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 1 * GB, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations().get(0)).contains("virtual-memory reservation, not confirmed");
    }

    @Test
    void stackReservationWithConfirmedResidentRiskIsHigh() {
        // 300 threads * 1 MiB stack = 300 MiB reservation, >=20% of the 1 GiB limit (ratio breach).
        // 800 MiB already resident + 300 MiB reservation = 1100 MiB >= the 1 GiB limit, so fully
        // realizing the reservation would breach the container limit: confirmed resident risk.
        ThreadData threads = threads(300);
        MemoryData memory = memoryWithCurrent(256 * MB, 2 * GB, 1 * GB, 800 * MB);
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations().get(0)).contains("already resident");
    }

    @Test
    void stackReservationLargeButLittleResidentUsageIsNotEscalatedToHigh() {
        // Regression test for the audited false positive: many idle/shallow threads reserve a large
        // amount of virtual address space (2 GiB of 4 GiB limit, an easy ratio breach) while actual
        // resident usage is small (300 MiB), so realizing the whole reservation would not come close
        // to breaching the limit (2348 MiB < 4096 MiB). This must stay MEDIUM, not HIGH.
        ThreadData threads = threads(2048);
        MemoryData memory = memoryWithCurrent(256 * MB, 2 * GB, 4 * GB, 300 * MB);
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void modestStackReservationIsNotFlagged() {
        ThreadData threads = threads(40);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-FOOTPRINT-002")).isNull();
    }

    // --- MEM-THREAD-002: absolute trigger now requires minimum ratio -------------------------

    @Test
    void manyBlockedThreadsInLargePoolBelowRatioThresholdNotFlagged() {
        // 25 blocked out of 500 = 5% < 10% MIN_RATIO_FOR_ABSOLUTE
        ThreadData threads = new ThreadData(
                500,
                500,
                0,
                false,
                false,
                List.of(),
                List.of(new ThreadStateCountDto("BLOCKED", 25), new ThreadStateCountDto("RUNNABLE", 475)),
                List.of());
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                threads,
                PostGcHeapData.unavailable(),
                healthyRuntime());

        assertThat(find(scan(context), "MEM-THREAD-002")).isNull();
    }

    @Test
    void blockedThreadsAbsoluteWithSufficientRatioIsFlagged() {
        // 25 blocked out of 100 = 25% >= both thresholds
        ThreadData threads = new ThreadData(
                100,
                100,
                0,
                false,
                false,
                List.of(),
                List.of(new ThreadStateCountDto("BLOCKED", 25), new ThreadStateCountDto("RUNNABLE", 75)),
                List.of());
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                threads,
                PostGcHeapData.unavailable(),
                healthyRuntime());

        assertThat(find(scan(context), "MEM-THREAD-002")).isNotNull();
    }

    // --- MEM-CONTENT-003: all array types excluded -------------------------------------------

    @Test
    void objectArrayDoesNotTriggerDominantClassRule() {
        // Object[] is routinely the top class; it should be excluded, not flagged
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "Object[]", 100_000, 80 * MB),
                new HeapClassHistogramEntryDto(2, "java.lang.String", 50_000, 10 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 150_000, 120 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        assertThat(find(scan(context), "MEM-CONTENT-003")).isNull();
    }

    @Test
    void nonArrayClassDominatingHeapIsFlagged() {
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "com.example.BigCache", 500, 80 * MB),
                new HeapClassHistogramEntryDto(2, "java.lang.String", 50_000, 5 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 50_500, 100 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        assertThat(find(scan(context), "MEM-CONTENT-003")).isNotNull();
    }

    // --- MEM-CONTENT-004: array dominance ----------------------------------------------------

    @Test
    void arrayDominanceIsFlagged() {
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "byte[]", 1_000, 80 * MB),
                new HeapClassHistogramEntryDto(2, "char[]", 1_000, 40 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 2_000, 150 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        MemoryReport report = scan(context);

        assertThat(find(report, "MEM-CONTENT-004")).isNotNull();
        assertThat(find(report, "MEM-CONTENT-002")).isNull();
    }

    @Test
    void modestArrayShareIsNotFlagged() {
        List<HeapClassHistogramEntryDto> histogram =
                List.of(new HeapClassHistogramEntryDto(1, "byte[]", 1_000, 20 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 1_000, 150 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        assertThat(find(scan(context), "MEM-CONTENT-004")).isNull();
    }

    // --- MEM-CONTENT-002: dynamic severity ---------------------------------------------------

    @Test
    void largeCollectionShareIsMedium() {
        List<HeapClassHistogramEntryDto> histogram =
                List.of(new HeapClassHistogramEntryDto(1, "java.util.HashMap$Node", 2_000_000, 120 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 2_000_000, 150 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-CONTENT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void smallerUncorroboratedCollectionIsLow() {
        List<HeapClassHistogramEntryDto> histogram =
                List.of(new HeapClassHistogramEntryDto(1, "java.util.ArrayList", 1_000, 60 * MB));
        HeapContentData heap = new HeapContentData(true, histogram, 1_000, 600 * MB);
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()), ThreadData.empty(), heap, healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-CONTENT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("LOW");
    }

    // --- MEM-THREAD-003: historical churn recast ---------------------------------------------

    @Test
    void peakThreadGapIsReportedAsInformationalHistory() {
        ThreadData threads = new ThreadData(
                50, 200, 10, false, false, List.of(), List.of(new ThreadStateCountDto("RUNNABLE", 50)), List.of());
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                threads,
                PostGcHeapData.unavailable(),
                healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-THREAD-003");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("INFO");
    }

    // --- MEM-GC-003: recent GC overhead (cross-scan trend) -----------------------------------

    @Test
    void firstScanEstablishesGcBaselineWithoutFlagging() {
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                PostGcHeapData.unavailable(),
                new RuntimeData(600_000, 10_000, 100, 0, -1, MB, 4, -1, -1, null));
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        assertThat(find(scanner.scan(), "MEM-GC-003")).isNull();
    }

    @Test
    void highRecentGcOverheadIsFlaggedOnSecondScan() {
        MemoryContext first = gcContext(600_000, 10_000, 100);
        MemoryContext second = gcContext(620_000, 16_000, 130);
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-GC-003");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void shortWindowBetweenScansSkipsRecentGcOverhead() {
        MemoryContext first = gcContext(600_000, 10_000, 100);
        MemoryContext second = gcContext(605_000, 16_000, 130);
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();

        assertThat(find(scanner.scan(), "MEM-GC-003")).isNull();
    }

    @Test
    void recentGcOverheadExcludesTheScansOwnForcedGc() {
        MemoryContext first = gcContext(600_000, 10_000, 100);
        MemoryContext second = new MemoryContext(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                PostGcHeapData.unavailable(),
                ClassLoadingData.empty(),
                new RuntimeData(620_000, 30_000, 200, 0, -1, MB, 4, -1, -1, null),
                new GcSample(619_000, 16_000, 130),
                GcTrend.unavailable());
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-GC-003");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("6000 ms");
    }

    // --- MEM-GC-002: concurrent-cycle beans excluded from overhead ---------------------------

    @Test
    void concurrentCycleBeanIsRecognised() {
        assertThat(MemoryCollector.isConcurrentCycleBean("ZGC Cycles")).isTrue();
        assertThat(MemoryCollector.isConcurrentCycleBean("ZGC Major Cycles")).isTrue();
        assertThat(MemoryCollector.isConcurrentCycleBean("Shenandoah Cycles")).isTrue();
        assertThat(MemoryCollector.isConcurrentCycleBean("G1 Concurrent GC")).isTrue();
        assertThat(MemoryCollector.isConcurrentCycleBean("ConcurrentMarkSweep")).isTrue();
        assertThat(MemoryCollector.isConcurrentCycleBean("G1 Young Generation")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean("G1 Old Generation")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean("ZGC Pauses")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean("Shenandoah Pauses")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean("Copy")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean("MarkSweepCompact")).isFalse();
        assertThat(MemoryCollector.isConcurrentCycleBean(null)).isFalse();
    }

    // --- MEM-POOL-005: Compressed Class Space -----------------------------------------------

    @Test
    void compressedClassSpaceNearMaxIsFlagged() {
        MemoryPoolSnapshot ccs = new MemoryPoolSnapshot("Compressed Class Space", 900 * MB, 950 * MB, GB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(ccs), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-005");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void lowCompressedClassSpaceUsageIsNotFlagged() {
        MemoryPoolSnapshot ccs = new MemoryPoolSnapshot("Compressed Class Space", 200 * MB, 250 * MB, GB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(ccs), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-005")).isNull();
    }

    // --- MEM-FOOTPRINT-003: container memory current usage ----------------------------------

    @Test
    void containerCurrentNearLimitIsFlagged() {
        MemoryData memory = memoryWithCurrent(256 * MB, 2 * GB, 4 * GB, 3700L * MB);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-003");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void containerCurrentWithNoLimitIsSkipped() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-FOOTPRINT-003")).isNull();
    }

    @Test
    void containerCurrentWellBelowLimitIsNotFlagged() {
        MemoryData memory = memoryWithCurrent(256 * MB, 2 * GB, 4 * GB, GB);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-FOOTPRINT-003")).isNull();
    }

    // --- MEM-GC-004: Serial GC on multi-core ------------------------------------------------

    @Test
    void serialGcOnMultiCoreIsFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of(), List.of("Copy", "MarkSweepCompact"));
        RuntimeData runtime = runtimeWithCpus(4);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        MemoryRuleResultDto result = find(scan(context), "MEM-GC-004");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("4-CPU");
    }

    @Test
    void serialGcOnSingleCpuIsSkipped() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of(), List.of("Copy", "MarkSweepCompact"));
        RuntimeData runtime = runtimeWithCpus(1);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-GC-004")).isNull();
    }

    @Test
    void g1GcDoesNotTriggerSerialGcRule() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of(), List.of("G1 Young Generation"));
        RuntimeData runtime = runtimeWithCpus(8);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-GC-004")).isNull();
    }

    @Test
    void serialGcOnMultiCoreWithSmallContainerMemoryIsSkipped() {
        // Below Oracle's historical "server-class machine" threshold (2 CPUs AND ~2 GiB memory):
        // a 512 MiB container limit means Serial GC is the JVM's own correct ergonomics choice here,
        // even though 4 CPUs are visible.
        MemoryData memory =
                memory(256 * MB, 2 * GB, List.of(), 512 * MB, List.of(), List.of("Copy", "MarkSweepCompact"));
        RuntimeData runtime = runtimeWithCpus(4);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-GC-004")).isNull();
    }

    @Test
    void serialGcOnMultiCoreWithSufficientContainerMemoryIsFlagged() {
        // 4 CPUs and a 4 GiB container limit are both above the "server-class machine" threshold, so
        // Serial GC is a genuine misconfiguration here.
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 4 * GB, List.of(), List.of("Copy", "MarkSweepCompact"));
        RuntimeData runtime = runtimeWithCpus(4);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        MemoryRuleResultDto result = find(scan(context), "MEM-GC-004");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("4-CPU");
    }

    // --- MEM-GC-005: G1 Full GC frequency ---------------------------------------------------

    @Test
    void g1FullGcCountIncreaseIsFlagged() {
        MemoryContext first =
                gcContextWithCollectors(600_000, 10_000, Map.of("G1 Young Generation", 100L, "G1 Old Generation", 0L));
        MemoryContext second =
                gcContextWithCollectors(620_000, 11_000, Map.of("G1 Young Generation", 110L, "G1 Old Generation", 2L));
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-GC-005");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("2 time(s)");
        // MEM-GC-005 no longer characterizes the G1 fallback Full GC as single-threaded (parallelized
        // since JDK 10's JEP 307); it must instead frame the concern around the stop-the-world fallback.
        assertThat(result.sampleViolations().get(0)).doesNotContainIgnoringCase("single-threaded");
        assertThat(result.description()).containsIgnoringCase("not single-threaded");
        assertThat(result.description()).containsIgnoringCase("stop-the-world");
        assertThat(result.description()).contains("JEP 307");
    }

    @Test
    void noG1FullGcDoesNotTriggerRule() {
        MemoryContext first =
                gcContextWithCollectors(600_000, 10_000, Map.of("G1 Young Generation", 100L, "G1 Old Generation", 0L));
        MemoryContext second =
                gcContextWithCollectors(620_000, 11_000, Map.of("G1 Young Generation", 110L, "G1 Old Generation", 0L));
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();

        assertThat(find(scanner.scan(), "MEM-GC-005")).isNull();
    }

    // --- MEM-HEAP-007: over-provisioned heap ------------------------------------------------

    @Test
    void overProvisionedHeapAfterGcIsFlagged() {
        // committed 8 GiB, post-GC used 2 GiB → 4x ratio, 6 GiB slack
        MemoryData memory = new MemoryData(
                2 * GB,
                8 * GB,
                10 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of(),
                List.of("G1 Young Generation"),
                null,
                null);
        RuntimeData runtime = new RuntimeData(700_000, 2_000, 50, 0, -1, MB, 4, -1, -1, null);
        MemoryContext context =
                context(memory, ThreadData.empty(), new PostGcHeapData(true, 2 * GB, false, -1), runtime);

        MemoryRuleResultDto result = find(scan(context), "MEM-HEAP-007");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("INFO");
    }

    @Test
    void tightlyUsedHeapDoesNotTriggerOverProvisionedRule() {
        // committed 4 GiB, post-GC used 3 GiB → 1.3x ratio
        MemoryData memory = new MemoryData(
                3 * GB,
                4 * GB,
                5 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of(),
                List.of("G1 Young Generation"),
                null,
                null);
        RuntimeData runtime = new RuntimeData(700_000, 2_000, 50, 0, -1, MB, 4, -1, -1, null);
        MemoryContext context =
                context(memory, ThreadData.empty(), new PostGcHeapData(true, 3 * GB, false, -1), runtime);

        assertThat(find(scan(context), "MEM-HEAP-007")).isNull();
    }

    // --- MEM-POOL-006: interpreted JIT mode ------------------------------------------------

    @Test
    void xintFlagIsFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-Xint"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-006");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("-Xint");
    }

    @Test
    void tieredStopAtLevel1IsFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-XX:TieredStopAtLevel=1"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-006");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("tier 1");
    }

    @Test
    void tieredStopAtLevel4IsNotFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-XX:TieredStopAtLevel=4"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-006")).isNull();
    }

    // --- MEM-POOL-001: Metaspace saturation (test coverage gap) ------------------------------

    @Test
    void metaspaceNearMaxIsFlagged() {
        MemoryPoolSnapshot metaspace = new MemoryPoolSnapshot("Metaspace", 90 * MB, 95 * MB, 100 * MB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(metaspace), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-001");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("90% full");
    }

    @Test
    void metaspaceBelowThresholdIsNotFlagged() {
        MemoryPoolSnapshot metaspace = new MemoryPoolSnapshot("Metaspace", 50 * MB, 60 * MB, 100 * MB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(metaspace), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-001")).isNull();
    }

    @Test
    void metaspaceWithoutConfiguredMaxIsSkipped() {
        MemoryPoolSnapshot metaspace = new MemoryPoolSnapshot("Metaspace", 90 * MB, 95 * MB, -1);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(metaspace), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-001")).isNull();
    }

    // --- MEM-POOL-002: code cache saturation (test coverage gap) -----------------------------

    @Test
    void codeCacheSegmentNearMaxIsFlagged() {
        MemoryPoolSnapshot segment = new MemoryPoolSnapshot("CodeHeap 'non-nmethods'", 5 * MB, 5 * MB, 5 * MB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(segment), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-002");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("CodeHeap 'non-nmethods'");
    }

    @Test
    void codeCacheBelowThresholdIsNotFlagged() {
        MemoryPoolSnapshot segment = new MemoryPoolSnapshot("CodeHeap 'non-nmethods'", 2 * MB, 3 * MB, 5 * MB);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(segment), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-002")).isNull();
    }

    @Test
    void noCodeCachePoolIsSkipped() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-002")).isNull();
    }

    // --- MEM-POOL-003: direct buffer growth (test coverage gap) ------------------------------

    @Test
    void directBufferNearExplicitCapIsFlagged() {
        MemoryData memory = new MemoryData(
                256 * MB,
                256 * MB,
                2 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                90 * MB,
                90 * MB,
                20,
                100 * MB,
                List.of("-XX:MaxDirectMemorySize=100m"),
                List.of("G1 Young Generation"),
                null,
                null);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-003");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("MaxDirectMemorySize");
    }

    @Test
    void directBufferNearEffectiveHeapCapIsFlagged() {
        // -XX:MaxDirectMemorySize is unset, so HotSpot's effective default cap equals max heap (-Xmx).
        MemoryData memory = new MemoryData(
                256 * MB,
                256 * MB,
                1 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                900 * MB,
                900 * MB,
                20,
                -1,
                List.of("-Xmx1g"),
                List.of("G1 Young Generation"),
                null,
                null);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-POOL-003");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("effective default cap");
    }

    @Test
    void modestDirectBufferUsageIsNotFlagged() {
        MemoryData memory = new MemoryData(
                256 * MB,
                256 * MB,
                2 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                10 * MB,
                10 * MB,
                5,
                100 * MB,
                List.of("-XX:MaxDirectMemorySize=100m"),
                List.of("G1 Young Generation"),
                null,
                null);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-POOL-003")).isNull();
    }

    // --- MEM-GC-001: missing heap sizing in container (test coverage gap) --------------------

    @Test
    void containerLimitWithoutHeapSizingIsFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 4 * GB, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-GC-001");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("-Xmx");
    }

    @Test
    void containerLimitWithExplicitMaxHeapIsNotFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 4 * GB, List.of("-Xmx2g"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-GC-001")).isNull();
    }

    @Test
    void containerLimitWithRamPercentageIsNotFlagged() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 4 * GB, List.of("-XX:MaxRAMPercentage=75.0"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-GC-001")).isNull();
    }

    @Test
    void noContainerLimitIsSkipped() {
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of());
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-GC-001")).isNull();
    }

    // --- MEM-FOOTPRINT-004: high swap utilization (test coverage gap) ------------------------

    @Test
    void noSwapConfiguredIsSkipped() {
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, 0, 0, null);
        MemoryContext context =
                context(healthyMemoryForSwap(), ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-FOOTPRINT-004")).isNull();
    }

    @Test
    void swapStatsUnavailableIsSkipped() {
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, -1, -1, null);
        MemoryContext context =
                context(healthyMemoryForSwap(), ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-FOOTPRINT-004")).isNull();
    }

    @Test
    void swapBelowThresholdIsNotFlagged() {
        // 10% swap used (well under the 50% threshold), regardless of JVM footprint.
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, 900 * MB, 1 * GB, null);
        MemoryContext context =
                context(healthyMemoryForSwap(), ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        assertThat(find(scan(context), "MEM-FOOTPRINT-004")).isNull();
    }

    @Test
    void highSwapWithFootprintExceedingFreePhysicalIsFlagged() {
        // 90% swap used, well above the 50% threshold. The committed footprint is set to an
        // unrealistically large value (hundreds of petabytes) so that it exceeds the free physical
        // memory of any real test/CI machine, deterministically exercising the violation branch
        // without needing to mock the live OperatingSystemMXBean read.
        long hugeFootprint = 900_000L * GB;
        MemoryData memory = new MemoryData(
                hugeFootprint,
                hugeFootprint,
                hugeFootprint * 2,
                0,
                0,
                0,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of(),
                List.of("G1 Young Generation"),
                null,
                null);
        RuntimeData runtime = new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, 100 * MB, 1 * GB, null);
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), runtime);

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-004");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("90%");
    }

    // --- MEM-CLASS-001: excessive loaded classes (test coverage gap) -------------------------

    @Test
    void manyLoadedClassesWithLittleUnloadingIsFlagged() {
        MemoryContext context = classLoadingContext(60_000, 60_000, 100);

        MemoryRuleResultDto result = find(scan(context), "MEM-CLASS-001");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("60000 classes");
        // The framework-fairness caveat (item 6): Spring-style runtime proxy/autoconfiguration class
        // generation structurally loads more classes than an equivalent build-time framework, so the
        // description must call this out rather than implying the threshold is framework-neutral.
        assertThat(result.description()).containsIgnoringCase("framework");
    }

    @Test
    void manyLoadedClassesWithActiveUnloadingIsNotFlagged() {
        // 700 unloaded out of 60,000 loaded is >= the 1% (1-in-100) unloading ratio, so this reads as
        // a legitimately large application rather than a classloader leak.
        MemoryContext context = classLoadingContext(60_000, 60_000, 700);

        assertThat(find(scan(context), "MEM-CLASS-001")).isNull();
    }

    @Test
    void modestLoadedClassCountIsNotFlagged() {
        MemoryContext context = classLoadingContext(10_000, 10_000, 0);

        assertThat(find(scan(context), "MEM-CLASS-001")).isNull();
    }

    // --- MEM-GC-006: GC pause-latency outlier (new rule) -------------------------------------

    @Test
    void gcPauseAboveThresholdIsFlagged() {
        RuntimeData runtime = runtimeWithLastGcPause(1_500, "G1 Young Generation");
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                PostGcHeapData.unavailable(),
                runtime);

        MemoryRuleResultDto result = find(scan(context), "MEM-GC-006");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations().get(0)).contains("1500 ms").contains("G1 Young Generation");
    }

    @Test
    void gcPauseBelowThresholdIsNotFlagged() {
        RuntimeData runtime = runtimeWithLastGcPause(200, "G1 Young Generation");
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                PostGcHeapData.unavailable(),
                runtime);

        assertThat(find(scan(context), "MEM-GC-006")).isNull();
    }

    @Test
    void gcPauseUnavailableIsSkipped() {
        MemoryContext context = context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                PostGcHeapData.unavailable(),
                healthyRuntime());

        assertThat(find(scan(context), "MEM-GC-006")).isNull();
    }

    // --- MEM-POOL-007: buffer pool growth without release (new rule) -------------------------

    @Test
    void directBufferPoolGrowingEveryScanWithoutReleaseIsFlaggedMedium() {
        MemoryScanner scanner = new MemoryScanner(
                sequence(
                        bufferPoolContext(100 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(150 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(200 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(250 * MB, 10 * MB, 1 * GB)),
                CLOCK);

        scanner.scan();
        scanner.scan();
        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-POOL-007");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations().get(0)).contains("'direct'").contains("3 consecutive scans");
    }

    @Test
    void directBufferPoolGrowingAndCrossingStaticThresholdEscalatesToHigh() {
        // Same growth streak as above, but the aggregate direct-buffer capacity is also >= 80% of
        // -XX:MaxDirectMemorySize, so MEM-POOL-003's static threshold is independently crossed too.
        MemoryScanner scanner = new MemoryScanner(
                sequence(
                        bufferPoolContext(100 * MB, 900 * MB, 1 * GB),
                        bufferPoolContext(150 * MB, 900 * MB, 1 * GB),
                        bufferPoolContext(200 * MB, 900 * MB, 1 * GB),
                        bufferPoolContext(250 * MB, 900 * MB, 1 * GB)),
                CLOCK);

        scanner.scan();
        scanner.scan();
        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-POOL-007");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void bufferPoolUsageThatDipsResetsTheStreakAndIsNotFlagged() {
        MemoryScanner scanner = new MemoryScanner(
                sequence(
                        bufferPoolContext(100 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(150 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(120 * MB, 10 * MB, 1 * GB),
                        bufferPoolContext(180 * MB, 10 * MB, 1 * GB)),
                CLOCK);

        scanner.scan();
        scanner.scan();
        scanner.scan();

        assertThat(find(scanner.scan(), "MEM-POOL-007")).isNull();
    }

    // --- MEM-HEAP-008: old-generation usage trending upward (new rule) -----------------------

    @Test
    void oldGenerationUsageIncreasingEveryScanIsFlagged() {
        MemoryScanner scanner = new MemoryScanner(
                sequence(
                        oldGenContext(100 * MB),
                        oldGenContext(150 * MB),
                        oldGenContext(200 * MB),
                        oldGenContext(250 * MB)),
                CLOCK);

        scanner.scan();
        scanner.scan();
        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-HEAP-008");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations().get(0)).contains("3 consecutive scans");
    }

    @Test
    void oldGenerationUsageThatDipsResetsTheStreakAndIsNotFlagged() {
        MemoryScanner scanner = new MemoryScanner(
                sequence(
                        oldGenContext(100 * MB),
                        oldGenContext(150 * MB),
                        oldGenContext(120 * MB),
                        oldGenContext(180 * MB)),
                CLOCK);

        scanner.scan();
        scanner.scan();
        scanner.scan();

        assertThat(find(scanner.scan(), "MEM-HEAP-008")).isNull();
    }

    @Test
    void oldGenerationTrendFirstScanIsNotFlagged() {
        MemoryScanner scanner = new MemoryScanner(sequence(oldGenContext(100 * MB)), CLOCK);

        assertThat(find(scanner.scan(), "MEM-HEAP-008")).isNull();
    }

    // --- helpers -----------------------------------------------------------------------------

    private MemoryReport scan(MemoryContext context) {
        return new MemoryScanner(() -> context, CLOCK).scan();
    }

    private static MemoryRuleResultDto find(MemoryReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static Supplier<MemoryContext> sequence(MemoryContext... contexts) {
        AtomicInteger index = new AtomicInteger();
        return () -> contexts[Math.min(index.getAndIncrement(), contexts.length - 1)];
    }

    private static MemoryContext context(
            MemoryData memory, ThreadData threads, PostGcHeapData postGc, RuntimeData runtime) {
        return context(memory, threads, HeapContentData.unavailable(), postGc, runtime);
    }

    private static MemoryContext context(
            MemoryData memory, ThreadData threads, HeapContentData heap, RuntimeData runtime) {
        return context(memory, threads, heap, PostGcHeapData.unavailable(), runtime);
    }

    private static MemoryContext context(
            MemoryData memory, ThreadData threads, HeapContentData heap, PostGcHeapData postGc, RuntimeData runtime) {
        return new MemoryContext(
                memory, threads, heap, postGc, ClassLoadingData.empty(), runtime, null, GcTrend.unavailable());
    }

    private static MemoryContext gcContext(long uptimeMillis, long gcTimeMillis, long gcCount) {
        return context(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                PostGcHeapData.unavailable(),
                new RuntimeData(uptimeMillis, gcTimeMillis, gcCount, 0, -1, MB, 4, -1, -1, null));
    }

    private static MemoryContext gcContextWithCollectors(
            long uptimeMillis, long gcTimeMillis, Map<String, Long> collectorCounts) {
        long gcCount =
                collectorCounts.values().stream().mapToLong(Long::longValue).sum();
        return new MemoryContext(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                PostGcHeapData.unavailable(),
                ClassLoadingData.empty(),
                new RuntimeData(uptimeMillis, gcTimeMillis, gcCount, 0, -1, MB, 4, -1, -1, null),
                new GcSample(uptimeMillis, gcTimeMillis, gcCount, collectorCounts),
                GcTrend.unavailable());
    }

    private static RuntimeData healthyRuntime() {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, -1, -1, null);
    }

    private static RuntimeData runtimeWithCompressedOops(Boolean useCompressedOops) {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, -1, -1, useCompressedOops);
    }

    private static RuntimeData runtimeWithCpus(int cpus) {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB, cpus, -1, -1, null);
    }

    private static RuntimeData runtimeWithLastGcPause(long millis, String collectorName) {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB, 4, -1, -1, null, -1, millis, collectorName);
    }

    private static MemoryData healthyMemoryForSwap() {
        return memory(256 * MB, 2 * GB, List.of(), null, List.of());
    }

    private static MemoryContext classLoadingContext(int loadedClasses, long totalLoadedClasses, long unloadedClasses) {
        return new MemoryContext(
                healthyMemoryForSwap(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                PostGcHeapData.unavailable(),
                new ClassLoadingData(loadedClasses, totalLoadedClasses, unloadedClasses),
                healthyRuntime(),
                null,
                GcTrend.unavailable());
    }

    /**
     * Builds a context carrying a single "direct" {@link MemoryContext.BufferPoolSnapshot} reading
     * (for MEM-POOL-007's cross-scan growth trend) alongside the aggregate direct-buffer scalars
     * MEM-POOL-003 reads, so tests can independently control whether the static threshold also fires.
     */
    private static MemoryContext bufferPoolContext(
            long directPoolUsed, long directBufferCapacity, long maxDirectMemoryBytes) {
        MemoryData memory = new MemoryData(
                256 * MB,
                256 * MB,
                2 * GB,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                directBufferCapacity,
                directBufferCapacity,
                10,
                maxDirectMemoryBytes,
                List.of(),
                List.of("G1 Young Generation"),
                null,
                null,
                List.of(new MemoryContext.BufferPoolSnapshot("direct", directPoolUsed, directPoolUsed, 10)));
        return context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());
    }

    private static MemoryContext oldGenContext(long postGcOldGenUsedBytes) {
        return context(
                healthyMemoryForSwap(),
                ThreadData.empty(),
                new PostGcHeapData(true, postGcOldGenUsedBytes, true, postGcOldGenUsedBytes),
                healthyRuntime());
    }

    private static ThreadData threads(int total) {
        return new ThreadData(
                total,
                total,
                0,
                false,
                false,
                List.of(),
                List.of(new ThreadStateCountDto("RUNNABLE", total)),
                List.of());
    }

    private static MemoryData memory(
            long heapUsed, long heapMax, List<MemoryPoolSnapshot> pools, Long containerLimit, List<String> args) {
        return memory(heapUsed, heapMax, pools, containerLimit, args, List.of("G1 Young Generation"));
    }

    private static MemoryData memory(
            long heapUsed,
            long heapMax,
            List<MemoryPoolSnapshot> pools,
            Long containerLimit,
            List<String> args,
            List<String> gcNames) {
        return new MemoryData(
                heapUsed,
                heapUsed,
                heapMax,
                64 * MB,
                80 * MB,
                256 * MB,
                pools,
                0,
                0,
                0,
                -1,
                args,
                gcNames,
                containerLimit,
                null);
    }

    private static MemoryData memoryWithCurrent(
            long heapUsed, long heapMax, Long containerLimit, Long containerCurrent) {
        return new MemoryData(
                heapUsed,
                heapUsed,
                heapMax,
                64 * MB,
                80 * MB,
                256 * MB,
                List.of(),
                0,
                0,
                0,
                -1,
                List.of(),
                List.of("G1 Young Generation"),
                containerLimit,
                containerCurrent);
    }
}
