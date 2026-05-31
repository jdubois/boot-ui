package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureReport;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.architecture.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private ArchitectureScanner scanner(List<String> basePackages) {
        return new ArchitectureScanner(() -> basePackages, new ClassFileArchitectureImporter(), CLOCK);
    }

    @Test
    void initialReportIsNotScanned() {
        ArchitectureReport report = scanner(List.of(FIXTURES)).initialReport();

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
    }

    @Test
    void scanEvaluatesAllRulesAndDetectsStandardStreamViolation() {
        ArchitectureReport report = scanner(List.of(FIXTURES)).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(ArchitectureRuleRegistry.activeRules().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.results()).extracting(ArchitectureRuleResultDto::id).contains("ARCH-CODE-001");

        ArchitectureRuleResultDto standardStreams = report.results().stream()
                .filter(result -> result.id().equals("ARCH-CODE-001"))
                .findFirst()
                .orElseThrow();
        assertThat(standardStreams.status()).isEqualTo("VIOLATION");
        assertThat(standardStreams.violationCount()).isPositive();
        assertThat(standardStreams.sampleViolations()).isNotEmpty();
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.severityCounts()).extracting("severity").containsExactly("HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoBasePackagesProducesEmptyScannedReport() {
        ArchitectureReport report = scanner(List.of()).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }
}
