package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.crac.CracReadinessScanner.CracScanResult;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CracReadinessScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.crac.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private static final CracRuntimeStatusDto RUNTIME =
            new CracRuntimeStatusDto(false, false, "Test JVM", false, null, null, List.of(), "summary");

    private CracReadinessScanner scanner(List<String> basePackages) {
        return new CracReadinessScanner(() -> basePackages, new ClassFileCracImporter(), CLOCK);
    }

    @Test
    void initialResultIsNotScanned() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.initialResult(), RUNTIME);

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.checksRun()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
        assertThat(report.runtime()).isEqualTo(RUNTIME);
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void scanDetectsEveryReadinessFinding() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracScanResult result = scanner.scan();
        CracReadinessReport report = scanner.report(result, RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.checksRun())
                .isEqualTo(CracCheckRegistry.activeChecks().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.status()).isEqualTo("REVIEW"));
        assertThat(report.findings())
                .extracting(CracFindingDto::id)
                .contains(
                        "CRAC-RES-001",
                        "CRAC-NET-001",
                        "CRAC-THREAD-001",
                        "CRAC-TIME-001",
                        "CRAC-RANDOM-001",
                        "CRAC-SECRET-001",
                        "CRAC-LIFECYCLE-001");
        assertThat(report.findings().stream().map(CracFindingDto::severity).toList())
                .isSortedAccordingTo(Comparator.comparingInt(CracReadinessScannerTests::severityRank));
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
        assertThat(report.findingsFound()).isEqualTo(report.findings().size());
    }

    @Test
    void scanWithoutBasePackagesReportsNoClasses() {
        CracReadinessScanner scanner = scanner(List.of());
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void basePackageDetectionFailureDegradesToWarning() {
        CracReadinessScanner scanner = new CracReadinessScanner(
                () -> {
                    throw new IllegalStateException("boom");
                },
                new ClassFileCracImporter(),
                CLOCK);

        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("base packages could not be detected"));
    }

    private static int severityRank(String severity) {
        return List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").indexOf(severity);
    }
}
