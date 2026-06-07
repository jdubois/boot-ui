package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.ClassLoadingData;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.HeapContentData;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.MemoryData;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.ThreadData;
import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorReport;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorSeverityCountDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryAdvisorScannerTests {

    private static final int RULE_COUNT = 16;
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-06T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void initialReportIsNotScanned() {
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> healthyContext(), CLOCK);

        MemoryAdvisorReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.localOnly()).isTrue();
    }

    @Test
    void healthyRuntimeEvaluatesAllRulesWithoutViolations() {
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> healthyContext(), CLOCK);

        MemoryAdvisorReport report = scanner.scan();

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
        MemoryAdvisorContext context = new MemoryAdvisorContext(
                memory, threads, HeapContentData.unavailable(), new ClassLoadingData(8000, 8000, 0));
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> context, CLOCK);

        MemoryAdvisorReport report = scanner.scan();

        assertThat(report.results())
                .extracting(MemoryAdvisorRuleResultDto::id)
                .contains("MEM-THREAD-001", "MEM-THREAD-002");
        assertThat(report.results().get(0).severity()).isEqualTo("CRITICAL");
        assertThat(report.results().get(0).id()).isEqualTo("MEM-THREAD-001");
        assertThat(severityCount(report, "CRITICAL")).isEqualTo(1);
    }

    @Test
    void highHeapUtilizationIsFlagged() {
        MemoryData memory = new MemoryData(
                960 * MB,
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
                null);
        MemoryAdvisorContext context = new MemoryAdvisorContext(
                memory, ThreadData.empty(), HeapContentData.unavailable(), ClassLoadingData.empty());
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> context, CLOCK);

        MemoryAdvisorReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryAdvisorRuleResultDto::id).contains("MEM-HEAP-001");
    }

    @Test
    void collectionBloatFromHistogramIsFlagged() {
        List<HeapClassHistogramEntryDto> histogram = List.of(
                new HeapClassHistogramEntryDto(1, "java.util.HashMap$Node", 2_000_000, 120 * MB),
                new HeapClassHistogramEntryDto(2, "byte[]", 1000, 30 * MB));
        HeapContentData heapContent = new HeapContentData(true, histogram, 2_001_000, 150 * MB);
        MemoryAdvisorContext context =
                new MemoryAdvisorContext(healthyMemory(), ThreadData.empty(), heapContent, ClassLoadingData.empty());
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> context, CLOCK);

        MemoryAdvisorReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryAdvisorRuleResultDto::id).contains("MEM-CONTENT-002");
        assertThat(report.summary().histogramAvailable()).isTrue();
    }

    @Test
    void heapContentRulesSkipGracefullyWhenHistogramAbsent() {
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> healthyContext(), CLOCK);

        MemoryAdvisorReport report = scanner.scan();

        // Skipped rules are not reported as violations.
        assertThat(report.results())
                .extracting(MemoryAdvisorRuleResultDto::id)
                .doesNotContain("MEM-CONTENT-001", "MEM-CONTENT-002");
        assertThat(report.scan().status()).isEqualTo("SCANNED");
    }

    @Test
    void collectorFailureProducesErrorReport() {
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(
                () -> {
                    throw new IllegalStateException("beans unavailable");
                },
                CLOCK);

        MemoryAdvisorReport report = scanner.scan();

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
                120_000L,
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
        MemoryAdvisorContext context = new MemoryAdvisorContext(
                healthyMemory(), threads, HeapContentData.unavailable(), ClassLoadingData.empty());
        MemoryAdvisorScanner scanner = new MemoryAdvisorScanner(() -> context, CLOCK);

        MemoryAdvisorReport report = scanner.scan();

        assertThat(report.results()).extracting(MemoryAdvisorRuleResultDto::id).contains("MEM-THREAD-004");
    }

    private static int severityCount(MemoryAdvisorReport report, String severity) {
        return report.severityCounts().stream()
                .filter(count -> count.severity().equals(severity))
                .mapToInt(MemoryAdvisorSeverityCountDto::count)
                .findFirst()
                .orElse(0);
    }

    private static MemoryAdvisorContext healthyContext() {
        return new MemoryAdvisorContext(
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
                new ClassLoadingData(9000, 9500, 500));
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
                null);
    }
}
