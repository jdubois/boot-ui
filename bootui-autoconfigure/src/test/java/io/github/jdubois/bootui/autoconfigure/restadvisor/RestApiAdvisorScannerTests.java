package io.github.jdubois.bootui.autoconfigure.restadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestApiAdvisorReport;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiAdvisorScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private RestApiAdvisorScanner scanner(List<String> basePackages, boolean springdocPresent) {
        return new RestApiAdvisorScanner(
                () -> basePackages, new ClassFileRestApiAdvisorImporter(), () -> springdocPresent, CLOCK);
    }

    @Test
    void initialReportIsNotScanned() {
        RestApiAdvisorReport report = scanner(List.of(FIXTURES), false).initialReport();

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
    }

    @Test
    void scanEvaluatesAllRulesAndReturnsOnlyViolationsOrderedByImportance() {
        RestApiAdvisorReport report = scanner(List.of(FIXTURES), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(RestApiAdvisorRuleRegistry.activeRules().size());
        assertThat(report.controllersAnalyzed()).isPositive();
        assertThat(report.handlersAnalyzed()).isPositive();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.results())
                .extracting(RestApiAdvisorRuleResultDto::id)
                .contains("RAPI-DTO-001", "RAPI-VALID-001", "RAPI-MAP-001", "RAPI-NAME-001");
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoBasePackagesProducesEmptyScannedReport() {
        RestApiAdvisorReport report = scanner(List.of(), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void scanWithNoControllersProducesEmptyScannedReport() {
        RestApiAdvisorReport report = scanner(List.of("does.not.exist"), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void importFailureDegradesToStableReportInsteadOfThrowing() {
        RestApiAdvisorScanner scanner = new RestApiAdvisorScanner(
                () -> List.of(FIXTURES),
                basePackages -> {
                    throw new NoClassDefFoundError("simulated unresolvable class");
                },
                () -> false,
                CLOCK);

        RestApiAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().message()).contains("could not be imported");
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void springdocFlagEnablesDocumentationRules() {
        RestApiAdvisorReport report = scanner(List.of(FIXTURES), true).scan();

        assertThat(report.results())
                .extracting(RestApiAdvisorRuleResultDto::id)
                .contains("RAPI-DOC-001", "RAPI-DOC-002");
    }
}
