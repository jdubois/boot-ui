package io.github.jdubois.bootui.engine.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner.GraalVmScanResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraalVmReadinessScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.graalvm.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private GraalVmReadinessScanner scanner(List<String> basePackages) {
        return new GraalVmReadinessScanner(
                () -> basePackages, new ClassFileGraalVmImporter(), new GraalVmDependencyScanner(() -> ""), CLOCK);
    }

    @Test
    void initialResultIsNotScanned() {
        GraalVmReadinessReport report =
                scanner(List.of(FIXTURES)).initialResult().report();

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
                        "GRAAL-REFLECT-002",
                        "GRAAL-REFLECT-003",
                        "GRAAL-REFLECT-004",
                        "GRAAL-PROXY-001",
                        "GRAAL-RES-001",
                        "GRAAL-RES-002",
                        "GRAAL-SERVICE-001",
                        "GRAAL-SER-001",
                        "GRAAL-SER-002",
                        "GRAAL-INIT-001",
                        "GRAAL-INIT-002",
                        "GRAAL-CLASSGEN-001",
                        "GRAAL-SCAN-001",
                        "SPRING-AOT-001",
                        "SPRING-AOT-002",
                        "GRAAL-NATIVE-001",
                        "GRAAL-NATIVE-002");
        assertThat(report.findings().stream().map(GraalVmFindingDto::severity).toList())
                .isSortedAccordingTo(Comparator.comparingInt(GraalVmReadinessScannerTests::severityRank));
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

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
    void scanSurfacesClassImportFailures() {
        GraalVmReadinessReport report = new GraalVmReadinessScanner(
                        () -> List.of(FIXTURES),
                        ignored -> {
                            throw new IllegalStateException("bytecode unavailable");
                        },
                        new GraalVmDependencyScanner(() -> ""),
                        CLOCK)
                .scan(true)
                .report();

        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.checksRun()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.warnings())
                .containsExactly("Application classes could not be imported for analysis: bytecode unavailable");
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

    @Test
    void scanReportsDependencyTruncationWarning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path jar = dir.resolve("dep.jar");
        try (java.util.jar.JarOutputStream out =
                new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("com/example/App.class"));
            out.write("data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        int entries = GraalVmDependencyScanner.maxDependencies() + 3;
        String classPath =
                String.join(java.io.File.pathSeparator, java.util.Collections.nCopies(entries, jar.toString()));

        GraalVmReadinessReport report = new GraalVmReadinessScanner(
                        () -> List.of(),
                        new ClassFileGraalVmImporter(),
                        new GraalVmDependencyScanner(() -> classPath),
                        CLOCK)
                .scan(true)
                .report();

        assertThat(report.dependencies()).hasSize(GraalVmDependencyScanner.maxDependencies());
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("stopped after the first"));
    }

    @Test
    void checkEvaluationWrapsRuntimeExceptionAsErrorFinding() {
        GraalVmFindingDto finding = new ThrowingCheck().evaluate(null);

        assertThat(finding.status()).isEqualTo(GraalVmCheckSupport.ERROR);
        assertThat(finding.occurrenceCount()).isZero();
        assertThat(finding.sampleOccurrences()).hasSize(1);
        assertThat(finding.sampleOccurrences().get(0))
                .contains("Check could not be evaluated:")
                .contains("boom");
    }

    @Test
    void checkEvaluationWrapsLinkageErrorAsErrorFinding() {
        GraalVmFindingDto finding = new LinkageErrorCheck().evaluate(null);

        assertThat(finding.status()).isEqualTo(GraalVmCheckSupport.ERROR);
        assertThat(finding.occurrenceCount()).isZero();
        assertThat(finding.sampleOccurrences().get(0)).contains("missing");
    }

    @Test
    void checkThatIsNotApplicableSurfacesSkippedStatus() {
        GraalVmFindingDto finding = new NotApplicableCheck().evaluate(null);

        assertThat(finding.status()).isEqualTo(GraalVmCheckSupport.SKIPPED);
        assertThat(finding.occurrenceCount()).isZero();
        assertThat(finding.sampleOccurrences().get(0)).contains("not applicable");
    }

    @Test
    void directlyImplementedCheckReportsErrorInsteadOfThrowing() {
        // SerializationCheck implements GraalVmCheck directly (not via AbstractArchUnitGraalVmCheck); a null
        // class set forces its body to throw so the test exercises its own fail-closed try/catch.
        GraalVmFindingDto finding = new SerializationCheck().evaluate(new GraalVmContext(null, List.of()));

        assertThat(finding.status()).isEqualTo(GraalVmCheckSupport.ERROR);
        assertThat(finding.occurrenceCount()).isZero();
        assertThat(finding.sampleOccurrences().get(0)).contains("Check could not be evaluated:");
    }

    private static GraalVmCheckDefinition testCheckDefinition() {
        return new GraalVmCheckDefinition(
                "GRAAL-TEST-001",
                "Deliberately failing test check",
                GraalVmCategory.REFLECTION,
                "INFO",
                "Test-only check used to exercise the fail-closed base.",
                "No action required.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/");
    }

    private static final class ThrowingCheck extends AbstractArchUnitGraalVmCheck {

        ThrowingCheck() {
            super(testCheckDefinition());
        }

        @Override
        ArchRule rule(GraalVmContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LinkageErrorCheck extends AbstractArchUnitGraalVmCheck {

        LinkageErrorCheck() {
            super(testCheckDefinition());
        }

        @Override
        ArchRule rule(GraalVmContext context) {
            throw new NoClassDefFoundError("missing");
        }
    }

    private static final class NotApplicableCheck extends AbstractArchUnitGraalVmCheck {

        NotApplicableCheck() {
            super(testCheckDefinition());
        }

        @Override
        ArchRule rule(GraalVmContext context) {
            return null;
        }
    }

    private static int severityRank(String severity) {
        return List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").indexOf(severity);
    }
}
