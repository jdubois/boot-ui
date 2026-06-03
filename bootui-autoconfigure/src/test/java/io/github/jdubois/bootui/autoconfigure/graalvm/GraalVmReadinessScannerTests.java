package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraalVmReadinessScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.graalvm.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private GraalVmReadinessScanner scanner(List<String> basePackages) {
        return new GraalVmReadinessScanner(
                () -> basePackages,
                new ClassFileGraalVmImporter(),
                new GraalVmDependencyScanner(() -> ""),
                CLOCK);
    }

    @Test
    void initialResultIsNotScanned() {
        GraalVmReadinessReport report = scanner(List.of(FIXTURES)).initialResult().report();

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.checksRun()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
        assertThat(report.metadata().resourceEntries()).isEqualTo(3);
        assertThat(report.metadata().reflectionEntries()).isZero();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void scanDetectsReadinessFindingsAndBuildsMetadata() {
        GraalVmScanResult result = scanner(List.of(FIXTURES)).scan(true);
        GraalVmReadinessReport report = result.report();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.checksRun())
                .isEqualTo(GraalVmCheckRegistry.activeChecks().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.status()).isEqualTo("REVIEW"));
        assertThat(report.findings())
                .extracting(GraalVmFindingDto::id)
                .contains(
                        "GRAAL-REFLECT-001",
                        "GRAAL-PROXY-001",
                        "GRAAL-RES-001",
                        "GRAAL-SER-001",
                        "GRAAL-NATIVE-001");
        assertThat(report.findings().stream()
                        .map(GraalVmFindingDto::severity)
                        .toList())
                .isSortedAccordingTo(Comparator.comparingInt(GraalVmReadinessScannerTests::severityRank));
        assertThat(report.severityCounts()).extracting("severity").containsExactly("HIGH", "MEDIUM", "LOW", "INFO");

        assertThat(report.includeDependencies()).isTrue();
        assertThat(report.findingsFound()).isEqualTo(report.findings().size());

        assertThat(result.metadata().reflectionTypes())
                .contains(FIXTURES + ".SerializableModel", FIXTURES + ".PersonRecord");
        assertThat(result.metadata().serializationTypes()).contains(FIXTURES + ".SerializableModel");
        assertThat(report.metadata().reflectionEntries())
                .isEqualTo(result.metadata().reflectionTypes().size());
    }

    @Test
    void scanWithoutDependenciesSkipsDependencySurvey() {
        GraalVmReadinessReport report = scanner(List.of(FIXTURES)).scan(false).report();

        assertThat(report.includeDependencies()).isFalse();
        assertThat(report.dependencies()).isEmpty();
        assertThat(report.dependenciesAnalyzed()).isZero();
    }

    @Test
    void scanSurfacesDependencySurveyFailures() {
        GraalVmReadinessReport report = new GraalVmReadinessScanner(
                        () -> List.of(),
                        new ClassFileGraalVmImporter(),
                        new GraalVmDependencyScanner(() -> {
                            throw new IllegalStateException("classpath unavailable");
                        }),
                        CLOCK)
                .scan(true)
                .report();

        assertThat(report.dependencies()).isEmpty();
        assertThat(report.warnings())
                .containsExactly("Dependency metadata survey could not be completed: classpath unavailable");
    }

    @Test
    void scanWithNoBasePackagesProducesEmptyScannedReport() {
        GraalVmReadinessReport report = scanner(List.of()).scan(true).report();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.checksRun()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.findingsFound()).isZero();
    }

    private static int severityRank(String severity) {
        return List.of("HIGH", "MEDIUM", "LOW", "INFO").indexOf(severity);
    }
}
