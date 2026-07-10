package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemorySeverityCountDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
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
import org.junit.jupiter.api.Test;

class MemoryScannerTests {

    private static final int RULE_COUNT = 36;
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-06T08:00:00Z"), ZoneOffset.UTC);

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
    void runawayCpuThreadIsFlagged() {
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
                healthyMemory(), threads, HeapContentData.unavailable(), ClassLoadingData.empty(), healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-THREAD-004");
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
    void classLoadingChurnIsFlagged() {
        MemoryContext context = new MemoryContext(
                healthyMemory(),
                ThreadData.empty(),
                HeapContentData.unavailable(),
                new ClassLoadingData(9000, 60_000, 51_000),
                healthyRuntime());
        MemoryScanner scanner = new MemoryScanner(() -> context, CLOCK);

        MemoryReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryRuleResultDto::id).contains("MEM-CLASS-002");
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
