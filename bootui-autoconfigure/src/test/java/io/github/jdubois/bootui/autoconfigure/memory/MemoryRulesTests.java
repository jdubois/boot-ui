package io.github.jdubois.bootui.autoconfigure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.GcSample;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.GcTrend;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.PostGcHeapData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.ThreadData;
import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    // --- MEM-HEAP-004: compressed-oops with collector/alignment awareness --------------------

    @Test
    void compressedOopsCliffIsSkippedForZgc() {
        MemoryData memory = memory(10 * GB, 40 * GB, List.of(), null, List.of("-Xmx40g"), List.of("ZGC Cycles"));
        MemoryContext context = context(memory, ThreadData.empty(), PostGcHeapData.unavailable(), healthyRuntime());

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
    void largeAbsoluteStackReservationIsFlagged() {
        ThreadData threads = threads(1100);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        MemoryRuleResultDto result = find(scan(context), "MEM-FOOTPRINT-002");

        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void stackReservationLargeRelativeToContainerIsFlagged() {
        ThreadData threads = threads(300);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), 1 * GB, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-FOOTPRINT-002")).isNotNull();
    }

    @Test
    void modestStackReservationIsNotFlagged() {
        ThreadData threads = threads(40);
        MemoryData memory = memory(256 * MB, 2 * GB, List.of(), null, List.of("-Xmx2g"));
        MemoryContext context = context(memory, threads, PostGcHeapData.unavailable(), healthyRuntime());

        assertThat(find(scan(context), "MEM-FOOTPRINT-002")).isNull();
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
                new RuntimeData(600_000, 10_000, 100, 0, -1, MB));
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
        // Pre-histogram counters exclude this scan's forced GC; the post-histogram runtime (which
        // includes it) is only used as the baseline for the next window.
        MemoryContext first = gcContext(600_000, 10_000, 100);
        MemoryContext second = new MemoryContext(
                memory(256 * MB, 2 * GB, List.of(), null, List.of()),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                PostGcHeapData.unavailable(),
                ClassLoadingData.empty(),
                new RuntimeData(620_000, 30_000, 200, 0, -1, MB),
                new GcSample(619_000, 16_000, 130),
                GcTrend.unavailable());
        MemoryScanner scanner = new MemoryScanner(sequence(first, second), CLOCK);

        scanner.scan();
        MemoryRuleResultDto result = find(scanner.scan(), "MEM-GC-003");

        assertThat(result).isNotNull();
        assertThat(result.sampleViolations().get(0)).contains("6000 ms");
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
                new RuntimeData(uptimeMillis, gcTimeMillis, gcCount, 0, -1, MB));
    }

    private static RuntimeData healthyRuntime() {
        return new RuntimeData(300_000, 1_500, 50, 0, -1, MB);
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
                containerLimit);
    }
}
