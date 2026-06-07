package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private RestApiScanner scanner(List<String> basePackages, boolean springdocPresent) {
        return new RestApiScanner(() -> basePackages, new ClassFileRestApiImporter(), () -> springdocPresent, CLOCK);
    }

    @Test
    void initialReportIsNotScanned() {
        RestApiReport report = scanner(List.of(FIXTURES), false).initialReport();

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
        RestApiReport report = scanner(List.of(FIXTURES), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.rulesEvaluated())
                .isEqualTo(RestApiRuleRegistry.activeRules().size());
        assertThat(report.controllersAnalyzed()).isPositive();
        assertThat(report.handlersAnalyzed()).isPositive();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.results())
                .extracting(RestApiRuleResultDto::id)
                .contains("RAPI-DTO-001", "RAPI-VALID-001", "RAPI-MAP-001", "RAPI-NAME-001");
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.severityCounts()).extracting("severity").containsExactly("HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void scanWithNoBasePackagesProducesEmptyScannedReport() {
        RestApiReport report = scanner(List.of(), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.basePackages()).isEmpty();
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void scanWithNoControllersProducesEmptyScannedReport() {
        RestApiReport report = scanner(List.of("does.not.exist"), false).scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.controllersAnalyzed()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void importFailureDegradesToStableReportInsteadOfThrowing() {
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                basePackages -> {
                    throw new NoClassDefFoundError("simulated unresolvable class");
                },
                () -> false,
                CLOCK);

        RestApiReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().message()).contains("could not be imported");
        assertThat(report.results()).isEmpty();
        assertThat(report.violationsFound()).isZero();
    }

    @Test
    void springdocFlagEnablesDocumentationRules() {
        RestApiReport report = scanner(List.of(FIXTURES), true).scan();

        assertThat(report.results()).extracting(RestApiRuleResultDto::id).contains("RAPI-DOC-001", "RAPI-DOC-002");
    }
}
