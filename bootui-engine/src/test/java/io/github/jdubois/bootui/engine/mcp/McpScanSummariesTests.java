package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.core.dto.PentestingFindingDto;
import io.github.jdubois.bootui.core.dto.PentestingReport;
import io.github.jdubois.bootui.core.dto.PentestingScanStatusDto;
import io.github.jdubois.bootui.core.dto.PostgresDatabaseDto;
import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.engine.mcp.McpScanSummaries.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpScanSummariesTests {

    @Test
    void aRuleAdvisorScanListsItsActiveRulesMostSevereFirstAndPointsAtTheDetailsTool() {
        List<HibernateRuleResultDto> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            results.add(rule("HIB-" + i, i == 7 ? "HIGH" : "MEDIUM", i + 1, false));
        }
        results.add(rule("HIB-DISMISSED", "CRITICAL", 99, true));
        AdvisorViolationDetailsDto details = new AdvisorViolationDetailsDto("scan-7", 177, 177, 1000, false, List.of());
        HibernateReport report = new HibernateReport(
                true,
                "Local only.",
                List.of("com.example"),
                3,
                40,
                12,
                List.of(),
                new HibernateScanStatusDto("Hibernate", "COMPLETE", null, 1L, 40, 3, 12),
                results,
                List.of(),
                AdvisorEvidenceDto.unknown(),
                details);

        Map<String, Object> summary = McpScanSummaries.hibernate(report);

        assertThat(summary.keySet())
                .containsExactly(
                        "reportTool",
                        "detailsTool",
                        "scan",
                        "findingsFound",
                        "severityCounts",
                        "topFindings",
                        "moreFindings",
                        "evidence",
                        "violationDetails");
        assertThat(summary)
                .containsEntry("reportTool", "get_hibernate_report")
                .containsEntry("detailsTool", "get_hibernate_rule_violations")
                .containsEntry("findingsFound", 12)
                .containsEntry("moreFindings", 2)
                .containsEntry("violationDetails", details);
        @SuppressWarnings("unchecked")
        List<Finding> top = (List<Finding>) summary.get("topFindings");
        assertThat(top).hasSize(McpScanSummaries.TOP_FINDINGS);
        assertThat(top.get(0)).isEqualTo(new Finding("HIB-7", "Rule HIB-7", "HIGH", 8));
        assertThat(top.get(1).id()).isEqualTo("HIB-11");
        assertThat(top).extracting(Finding::id).doesNotContain("HIB-DISMISSED");
    }

    @Test
    void aFindingAdvisorScanHasNoDetailsToolAndLeavesDismissedFindingsOut() {
        PentestingReport report = new PentestingReport(
                true,
                "Local only.",
                5,
                1,
                List.of(),
                new PentestingScanStatusDto("probe", "COMPLETE", null, 1L, 5, 1),
                List.of(),
                List.of(finding("PT-1", "HIGH", false), finding("PT-2", "CRITICAL", true)),
                AdvisorEvidenceDto.unknown());

        Map<String, Object> summary = McpScanSummaries.pentest(report);

        assertThat(summary)
                .containsEntry("reportTool", "get_pentest_report")
                .containsEntry("detailsTool", null)
                .containsEntry("violationDetails", null)
                .containsEntry("findingsFound", 1)
                .containsEntry("moreFindings", 0)
                .containsEntry("topFindings", List.of(new Finding("PT-1", "Finding PT-1", "HIGH", 1)));
    }

    @Test
    void aVulnerabilityScanListsVulnerableDependenciesByTheirMostSevereActiveAdvisory() {
        DependencyDto risky = new DependencyDto(
                "org.example",
                "risky",
                "1.0",
                null,
                "maven",
                3,
                "CRITICAL",
                List.of(
                        advisory("GHSA-low", "LOW", false),
                        advisory("GHSA-dismissed", "CRITICAL", true),
                        advisory("GHSA-high", "HIGH", false)),
                null,
                null);
        DependencyDto safe =
                new DependencyDto("org.example", "safe", "2.0", null, "maven", 0, null, List.of(), null, null);
        DependenciesReport report =
                new DependenciesReport(true, 2, 1, List.of(), null, null, List.of(safe, risky), null, null);

        Map<String, Object> summary = McpScanSummaries.vulnerabilities(report);

        assertThat(summary)
                .containsEntry("reportTool", "get_vulnerabilities_report")
                .containsEntry("findingsFound", 1)
                .containsEntry("dependencies", 2)
                .containsEntry("scanningEnabled", true)
                .containsEntry(
                        "topFindings",
                        List.of(new Finding("org.example:risky:1.0", "GHSA-high: Advisory GHSA-high", "CRITICAL", 3)));
    }

    @Test
    void aPostgresqlReadSummarizesCoverageWithoutRows() {
        PostgresSectionDto sessions =
                new PostgresSectionDto("sessions", "Sessions", "AVAILABLE", "Role lacks pg_monitor.", null, 3, false);
        PostgresDatabaseDto database = new PostgresDatabaseDto(
                "dataSource",
                "app",
                "17.2",
                17,
                "app",
                false,
                "PARTIAL",
                null,
                null,
                List.of(sessions),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                false);
        PostgresInsightReport report = new PostgresInsightReport(
                true, "Local only.", "PARTIAL", null, 5L, 1, false, List.of(database), List.of(), List.of("note"));

        Map<String, Object> summary = McpScanSummaries.postgresql(report);

        assertThat(summary)
                .containsEntry("reportTool", "get_postgresql_report")
                .containsEntry("status", "PARTIAL")
                .containsEntry("databasesRead", 1)
                .containsEntry("diagnostics", 0)
                .containsEntry("limitations", List.of("note"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summarized = ((List<Map<String, Object>>) summary.get("databases")).get(0);
        assertThat(summarized)
                .containsEntry("name", "dataSource")
                .containsEntry("status", "PARTIAL")
                .containsEntry("truncated", false)
                .containsEntry(
                        "sections",
                        List.of(Map.of(
                                "id",
                                "sessions",
                                "status",
                                "AVAILABLE",
                                "reason",
                                "Role lacks pg_monitor.",
                                "rowCount",
                                3,
                                "truncated",
                                false)))
                .doesNotContainKeys("sessions", "statements", "vitalSigns");
    }

    @Test
    void aLongTitleIsCut() {
        HibernateReport report = new HibernateReport(
                true,
                null,
                List.of(),
                1,
                1,
                1,
                List.of(),
                null,
                List.of(new HibernateRuleResultDto(
                        "HIB-1",
                        "x".repeat(400),
                        "C",
                        "LOW",
                        null,
                        "FAILED",
                        1,
                        List.of(),
                        null,
                        null,
                        false,
                        null,
                        List.of())),
                List.of(),
                null,
                null);
        @SuppressWarnings("unchecked")
        List<Finding> top = (List<Finding>) McpScanSummaries.hibernate(report).get("topFindings");
        assertThat(top.get(0).title())
                .hasSize(McpScanSummaries.MAX_TITLE_LENGTH)
                .endsWith("…");
    }

    private static HibernateRuleResultDto rule(String id, String severity, int violations, boolean dismissed) {
        return new HibernateRuleResultDto(
                id,
                "Rule " + id,
                "PERFORMANCE",
                severity,
                "Description.",
                "FAILED",
                violations,
                List.of("sample"),
                "Recommendation.",
                null,
                dismissed,
                null,
                List.of());
    }

    private static PentestingFindingDto finding(String id, String severity, boolean dismissed) {
        return new PentestingFindingDto(
                id, "Finding " + id, "A01", severity, "HIGH", "/", "evidence", "fix", null, false, dismissed);
    }

    private static DependencyVulnerabilityDto advisory(String id, String severity, boolean dismissed) {
        return new DependencyVulnerabilityDto(
                id,
                "Advisory " + id,
                "details",
                severity,
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                dismissed,
                List.of(),
                null,
                null);
    }
}
