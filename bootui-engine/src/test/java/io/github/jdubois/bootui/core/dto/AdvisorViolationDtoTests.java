package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AdvisorViolationDtoTests {

    private static final AdvisorEvidenceDto EVIDENCE = new AdvisorEvidenceDto(true, false, List.of("Limited coverage"));
    private static final AdvisorViolationDetailsDto DETAILS = new AdvisorViolationDetailsDto("scan", 29, 22, 22, true);

    @Test
    void unknownMetadataDoesNotAdvertiseACompletedScan() {
        assertThat(AdvisorViolationDetailsDto.unknown())
                .isEqualTo(new AdvisorViolationDetailsDto(null, 0, 0, 0, false));
    }

    @Test
    void pageDefensivelyCopiesDetailsWithoutChangingOrderOrMultiplicity() {
        List<String> details = new ArrayList<>(List.of("last", "first", "first"));
        PageMetadata page = new PageMetadata(3, 3, 0, 100, 3, false);
        AdvisorRuleViolationsDto result = new AdvisorRuleViolationsDto("scan", "rule", 5, 3, true, details, page);
        details.clear();

        assertThat(result.violations()).containsExactly("last", "first", "first");
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.violationCount()).isEqualTo(5);
        assertThat(result.retainedCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThatThrownBy(() -> result.violations().add("changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new AdvisorRuleViolationsDto("scan", "rule", 1, 0, true, null, page).violations())
                .isEmpty();
    }

    @Test
    void architectureReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        ArchitectureRuleResultDto result = new ArchitectureRuleResultDto(
                "arch",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        ArchitectureReport original = new ArchitectureReport(
                true,
                "architecture",
                List.of("example"),
                31,
                21,
                11,
                List.of(new ArchitectureSeverityCountDto("HIGH", 1)),
                new ArchitectureScanStatusDto("analyzer", "PARTIAL", "message", 42L, 21, 31, 11),
                List.of(result),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, ArchitectureReport::withViolationDetails, ArchitectureReport::violationDetails);
    }

    @Test
    void hibernateReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        HibernateRuleResultDto result = new HibernateRuleResultDto(
                "hib",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        HibernateReport original = new HibernateReport(
                true,
                "hibernate",
                List.of("example"),
                32,
                22,
                12,
                List.of(new HibernateSeverityCountDto("HIGH", 1)),
                new HibernateScanStatusDto("analyzer", "PARTIAL", "message", 42L, 22, 32, 12),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, HibernateReport::withViolationDetails, HibernateReport::violationDetails);
    }

    @Test
    void springReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        SpringRuleResultDto result = new SpringRuleResultDto(
                "spring",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        SpringReport original = new SpringReport(
                true,
                "spring",
                List.of("example"),
                33,
                23,
                13,
                List.of(new SpringSeverityCountDto("HIGH", 1)),
                new SpringScanStatusDto("analyzer", "PARTIAL", "message", 42L, 23, 33, 13),
                List.of(result),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, SpringReport::withViolationDetails, SpringReport::violationDetails);
    }

    @Test
    void securityReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        SecurityRuleResultDto result = new SecurityRuleResultDto(
                "sec",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        SecurityReport original = new SecurityReport(
                true,
                "security",
                List.of("example"),
                34,
                24,
                14,
                List.of(new SecuritySeverityCountDto("HIGH", 1)),
                new SecurityScanStatusDto("analyzer", "PARTIAL", "message", 42L, 24, 34, 14),
                List.of(result),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, SecurityReport::withViolationDetails, SecurityReport::violationDetails);
    }

    @Test
    void restApiReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        RestApiRuleResultDto result = new RestApiRuleResultDto(
                "rest",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        RestApiReport original = new RestApiReport(
                true,
                "rest",
                List.of("example"),
                35,
                45,
                25,
                15,
                List.of(new RestApiSeverityCountDto("HIGH", 1)),
                new RestApiScanStatusDto("analyzer", "PARTIAL", "message", 42L, 25, 35, 45, 15),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, RestApiReport::withViolationDetails, RestApiReport::violationDetails);
    }

    @Test
    void memoryReportPreservesItsOriginalContractWhenMetadataIsAdded() {
        MemoryRuleResultDto result = new MemoryRuleResultDto(
                "mem",
                "name",
                "category",
                "HIGH",
                "description",
                "VIOLATION",
                3,
                List.of("detail"),
                "fix",
                "url",
                true);
        MemoryReport original = new MemoryReport(
                true,
                "memory",
                26,
                16,
                new MemorySummaryDto(10, 20, 50, 30, 40, true, 60, true),
                List.of(new MemorySeverityCountDto("HIGH", 1)),
                new MemoryScanStatusDto("analyzer", "PARTIAL", "message", 42L, 26, 16),
                List.of(result),
                List.of(result),
                EVIDENCE);

        assertMetadataCopy(original, MemoryReport::withViolationDetails, MemoryReport::violationDetails);
    }

    @Test
    void databaseReportPreservesScanTruncationSeparatelyFromDetailTruncation() {
        DatabaseAdvisorRuleResultDto result = new DatabaseAdvisorRuleResultDto(
                "db", "name", "category", "HIGH", "description", "VIOLATION", 3, List.of("detail"), "fix", "url", true);
        DatabaseAdvisorReport original = new DatabaseAdvisorReport(
                true,
                "database",
                List.of("example"),
                List.of(new DatabaseAdvisorDataSourceDto(
                        "source", "product", "dialect", "LOWER", "PARTIAL", "message", 37, true)),
                37,
                27,
                17,
                7,
                3,
                false,
                List.of(new DatabaseAdvisorSeverityCountDto("HIGH", 1)),
                new DatabaseAdvisorScanStatusDto("analyzer", "PARTIAL", "message", 42L, 27, 37, 17),
                List.of(result),
                List.of(new DatabaseAdvisorDiagnosticDto("source", "WARNING", "message")),
                EVIDENCE);

        assertMetadataCopy(
                original, DatabaseAdvisorReport::withViolationDetails, DatabaseAdvisorReport::violationDetails);
        assertThat(original.withViolationDetails(DETAILS).truncated()).isFalse();
        assertThat(original.withViolationDetails(DETAILS).violationDetails().truncated())
                .isTrue();
    }

    private static <R> void assertMetadataCopy(
            R original,
            BiFunction<R, AdvisorViolationDetailsDto, R> copy,
            Function<R, AdvisorViolationDetailsDto> metadata) {
        assertThat(metadata.apply(original)).isEqualTo(AdvisorViolationDetailsDto.unknown());
        R updated = copy.apply(original, DETAILS);
        assertThat(updated).isNotSameAs(original);
        assertThat(metadata.apply(updated)).isSameAs(DETAILS);
        assertThat(updated)
                .usingRecursiveComparison()
                .ignoringFields("violationDetails")
                .isEqualTo(original);
        assertThat(copy.apply(updated, null)).isEqualTo(original);
        assertThat(metadata.apply(original)).isEqualTo(AdvisorViolationDetailsDto.unknown());
    }
}
