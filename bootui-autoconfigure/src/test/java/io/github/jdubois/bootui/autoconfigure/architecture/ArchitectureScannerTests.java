package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
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
    void scanEvaluatesAllRulesAndReturnsOnlyViolationsOrderedByImportance() {
        ArchitectureReport report = scanner(List.of(FIXTURES)).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(ArchitectureRuleRegistry.activeRules().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.results())
                .extracting(ArchitectureRuleResultDto::id)
                .contains("ARCH-SPRING-004", "ARCH-SPRING-001", "ARCH-CODE-001")
                .doesNotContain("ARCH-CODE-004");
        assertThat(report.results().stream()
                        .map(ArchitectureRuleResultDto::severity)
                        .toList())
                .isSortedAccordingTo(Comparator.comparingInt(ArchitectureScannerTests::severityRank));

        ArchitectureRuleResultDto standardStreams = report.results().stream()
                .filter(result -> result.id().equals("ARCH-CODE-001"))
                .findFirst()
                .orElseThrow();
        assertThat(standardStreams.status()).isEqualTo("VIOLATION");
        assertThat(standardStreams.violationCount()).isPositive();
        assertThat(standardStreams.sampleViolations()).isNotEmpty();
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
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

    @Test
    void ruleEvaluationWrapsRuntimeExceptionAsErrorResult() {
        ArchitectureRuleResultDto result = new ThrowingRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0))
                .contains("Rule could not be evaluated:")
                .contains("boom");
    }

    @Test
    void ruleEvaluationWrapsLinkageErrorAsErrorResult() {
        ArchitectureRuleResultDto result = new LinkageErrorRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).contains("missing");
    }

    @Test
    void ruleThatIsNotApplicableSurfacesSkippedStatus() {
        ArchitectureRuleResultDto result = new NotApplicableRule().evaluate(null);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.SKIPPED);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).contains("not applicable");
    }

    @Test
    void everyActiveRuleRoutesThroughTheFailClosedBase() {
        assertThat(ArchitectureRuleRegistry.activeRules())
                .allSatisfy(rule -> assertThat(rule).isInstanceOf(AbstractArchitectureRule.class));
    }

    private static ArchitectureRuleDefinition testRuleDefinition() {
        return new ArchitectureRuleDefinition(
                "ARCH-TEST-001",
                "Deliberately failing test rule",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Test-only rule used to exercise the fail-closed base.",
                "No action required.",
                "https://www.archunit.org/userguide/html/000_Index.html");
    }

    private static final class ThrowingRule extends AbstractArchitectureRule {

        ThrowingRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LinkageErrorRule extends AbstractArchitectureRule {

        LinkageErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            throw new NoClassDefFoundError("missing");
        }
    }

    private static final class NotApplicableRule extends AbstractArchitectureRule {

        NotApplicableRule() {
            super(testRuleDefinition());
        }

        @Override
        ArchRule rule(ArchitectureContext context) {
            return null;
        }
    }

    @Test
    void analysisErrorsKeepsOnlyErrorResultsSortedById() {
        ArchitectureRuleResultDto pass = result("ARCH-T-001", ArchitectureRuleSupport.PASS);
        ArchitectureRuleResultDto violation = result("ARCH-T-002", ArchitectureRuleSupport.VIOLATION);
        ArchitectureRuleResultDto errorB = result("ARCH-T-004", ArchitectureRuleSupport.ERROR);
        ArchitectureRuleResultDto errorA = result("ARCH-T-003", ArchitectureRuleSupport.ERROR);
        ArchitectureRuleResultDto skipped = result("ARCH-T-005", ArchitectureRuleSupport.SKIPPED);

        List<ArchitectureRuleResultDto> errors =
                ArchitectureScanner.analysisErrors(List.of(pass, violation, errorB, errorA, skipped));

        assertThat(errors).extracting(ArchitectureRuleResultDto::id).containsExactly("ARCH-T-003", "ARCH-T-004");
        assertThat(errors).extracting(ArchitectureRuleResultDto::status).containsOnly(ArchitectureRuleSupport.ERROR);
    }

    private static ArchitectureRuleResultDto result(String id, String status) {
        return new ArchitectureRuleResultDto(
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

    private static int severityRank(String severity) {
        return List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").indexOf(severity);
    }
}
