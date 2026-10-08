package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MySqlDataSourceDto;
import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.core.dto.MySqlSectionDto;
import io.github.jdubois.bootui.core.dto.PentestingReport;
import io.github.jdubois.bootui.core.dto.PostgresDatabaseDto;
import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The compact answer every active scan or read tool gives an agent, on every stack.
 *
 * <p>An active scan used to answer with the whole report its {@code get_*_report} tool returns, so an agent paid for
 * the full report twice: hundreds of kilobytes for a vulnerability scan. The browser keeps the full report. An agent
 * gets what tells it whether to look further, and the tool that holds the rest:
 *
 * <ul>
 *   <li>{@code reportTool} reads the cached report this scan produced, without scanning again; {@code detailsTool}
 *       pages a rule's retained violations, and is {@code null} for an advisor that has none;
 *   <li>{@code scan} is the scan status as the report carries it, and {@code evidence} the advisor's coverage;
 *   <li>{@code findingsFound} and {@code severityCounts} are the report's whole counts, dismissed findings excluded;
 *   <li>{@code topFindings} lists at most {@value #TOP_FINDINGS} findings, most severe first then most frequent, each
 *       with its {@code id}, {@code title}, {@code severity} and {@code count}; {@code moreFindings} counts the
 *       findings left out, so {@code 0} means every finding is listed;
 *   <li>{@code violationDetails} carries the {@code scanId} that the {@code detailsTool} pages, for the rule
 *       advisors.
 * </ul>
 *
 * <p>The PostgreSQL and MySQL reads are runtime views that grade nothing, so their summary is the read's status and
 * each database's and section's coverage instead.
 */
public final class McpScanSummaries {

    /** The most findings a summary lists. */
    public static final int TOP_FINDINGS = 10;

    /** The longest title a summary row carries, in characters. */
    static final int MAX_TITLE_LENGTH = 160;

    private static final List<String> VULNERABILITY_SEVERITIES =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN", "NONE");

    private McpScanSummaries() {}

    /**
     * One summarized finding.
     *
     * @param id the rule, check, or finding id; a vulnerable dependency's coordinates
     * @param title the rule or finding name; a vulnerable dependency's most severe advisory id and summary
     * @param severity the finding's severity; a dependency's highest
     * @param count the violations, occurrences, or advisories the finding stands for
     */
    public record Finding(String id, String title, String severity, int count) {}

    public static Map<String, Object> architecture(ArchitectureReport report) {
        return rules(
                "architecture",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> spring(SpringReport report) {
        return rules(
                "spring",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> hibernate(HibernateReport report) {
        return rules(
                "hibernate",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> memory(MemoryReport report) {
        return rules(
                "memory",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> security(SecurityReport report) {
        return rules(
                "security",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> restApi(RestApiReport report) {
        return rules(
                "rest_api",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> databaseAdvisor(DatabaseAdvisorReport report) {
        return rules(
                "database_advisor",
                report.scan(),
                report.violationsFound(),
                report.severityCounts(),
                report.evidence(),
                report.violationDetails(),
                rows(
                        report.results(),
                        r -> r.dismissed() ? null : rule(r.id(), r.name(), r.severity(), r.violationCount())));
    }

    public static Map<String, Object> pentest(PentestingReport report) {
        return summary(
                "get_pentest_report",
                null,
                report.scan(),
                report.findingsFound(),
                report.severityCounts(),
                report.evidence(),
                null,
                rows(report.findings(), f -> f.dismissed() ? null : new Finding(f.id(), f.title(), f.severity(), 1)),
                SeverityOrder.DEFAULT);
    }

    public static Map<String, Object> graalvm(GraalVmReadinessReport report) {
        return summary(
                "get_graalvm_report",
                null,
                report.scan(),
                report.findingsFound(),
                report.severityCounts(),
                null,
                null,
                rows(report.findings(), f -> new Finding(f.id(), f.name(), f.severity(), f.occurrenceCount())),
                SeverityOrder.DEFAULT);
    }

    public static Map<String, Object> crac(CracReadinessReport report) {
        return summary(
                "get_crac_report",
                null,
                report.scan(),
                report.findingsFound(),
                report.severityCounts(),
                null,
                null,
                rows(report.findings(), f -> new Finding(f.id(), f.name(), f.severity(), f.occurrenceCount())),
                SeverityOrder.DEFAULT);
    }

    /**
     * A vulnerability scan's summary: one finding per vulnerable dependency, identified by its coordinates and titled
     * with its most severe advisory, plus the scan's inventory totals and coverage.
     */
    public static Map<String, Object> vulnerabilities(DependenciesReport report) {
        List<Finding> rows = new ArrayList<>();
        for (DependencyDto dependency : report.dependencies()) {
            if (dependency.vulnerabilityCount() > 0) {
                rows.add(new Finding(
                        coordinates(dependency),
                        mostSevereAdvisory(dependency.vulnerabilities()),
                        dependency.highestSeverity(),
                        dependency.vulnerabilityCount()));
            }
        }
        Map<String, Object> summary = summary(
                "get_vulnerabilities_report",
                null,
                report.scan(),
                report.vulnerable(),
                report.severityCounts(),
                report.evidence(),
                null,
                rows,
                VULNERABILITY_SEVERITIES);
        summary.put("scanningEnabled", report.scanningEnabled());
        summary.put("dependencies", report.total());
        summary.put("coverage", report.coverage());
        return summary;
    }

    /** A PostgreSQL read's summary: the read's status and each database's and section's coverage, without rows. */
    public static Map<String, Object> postgresql(PostgresInsightReport report) {
        List<Map<String, Object>> databases = new ArrayList<>();
        for (PostgresDatabaseDto database : report.databases()) {
            List<Map<String, Object>> sections = new ArrayList<>();
            for (PostgresSectionDto section : database.sections()) {
                sections.add(section(
                        section.id(), section.status(), section.reason(), section.rowCount(), section.truncated()));
            }
            databases.add(
                    database(database.name(), database.status(), database.message(), database.truncated(), sections));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reportTool", "get_postgresql_report");
        summary.put("status", report.status());
        summary.put("message", report.message());
        summary.put("readAt", report.readAt());
        summary.put("databasesRead", report.databasesRead());
        summary.put("truncated", report.truncated());
        summary.put("databases", databases);
        summary.put("diagnostics", report.diagnostics().size());
        summary.put("limitations", report.limitations());
        return summary;
    }

    /** A MySQL read's summary: the read's status and each datasource's and section's coverage, without rows. */
    public static Map<String, Object> mysql(MySqlInsightReport report) {
        List<Map<String, Object>> dataSources = new ArrayList<>();
        for (MySqlDataSourceDto dataSource : report.dataSources()) {
            List<Map<String, Object>> sections = new ArrayList<>();
            for (MySqlSectionDto section : dataSource.sections()) {
                sections.add(section(
                        section.id(), section.status(), section.reason(), section.rowCount(), section.truncated()));
            }
            dataSources.add(database(
                    dataSource.name(), dataSource.status(), dataSource.message(), dataSource.truncated(), sections));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reportTool", "get_mysql_report");
        summary.put("status", report.status());
        summary.put("message", report.message());
        summary.put("readAt", report.readAt());
        summary.put("dataSourcesRead", report.dataSourcesRead());
        summary.put("truncated", report.truncated());
        summary.put("dataSources", dataSources);
        summary.put("diagnostics", report.diagnostics().size());
        summary.put("limitations", report.limitations());
        return summary;
    }

    private static Map<String, Object> rules(
            String advisor,
            Object scan,
            int findingsFound,
            Object severityCounts,
            AdvisorEvidenceDto evidence,
            AdvisorViolationDetailsDto violationDetails,
            List<Finding> rows) {
        return summary(
                "get_" + advisor + "_report",
                "get_" + advisor + "_rule_violations",
                scan,
                findingsFound,
                severityCounts,
                evidence,
                violationDetails,
                rows,
                SeverityOrder.DEFAULT);
    }

    private static Map<String, Object> summary(
            String reportTool,
            String detailsTool,
            Object scan,
            int findingsFound,
            Object severityCounts,
            AdvisorEvidenceDto evidence,
            AdvisorViolationDetailsDto violationDetails,
            List<Finding> rows,
            List<String> severityOrder) {
        List<Finding> ranked = new ArrayList<>(rows);
        ranked.sort(Comparator.comparingInt((Finding row) -> SeverityOrder.rank(severityOrder, row.severity()))
                .thenComparing(Comparator.comparingInt(Finding::count).reversed()));
        List<Finding> listed = List.copyOf(ranked.subList(0, Math.min(TOP_FINDINGS, ranked.size())));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reportTool", reportTool);
        summary.put("detailsTool", detailsTool);
        summary.put("scan", scan);
        summary.put("findingsFound", findingsFound);
        summary.put("severityCounts", severityCounts);
        summary.put("topFindings", listed);
        summary.put("moreFindings", ranked.size() - listed.size());
        summary.put("evidence", evidence);
        summary.put("violationDetails", violationDetails);
        return summary;
    }

    private static <T> List<Finding> rows(List<T> results, Function<T, Finding> row) {
        List<Finding> rows = new ArrayList<>();
        for (T result : results) {
            Finding finding = row.apply(result);
            if (finding != null) {
                rows.add(finding);
            }
        }
        return rows;
    }

    private static Finding rule(String id, String name, String severity, int violationCount) {
        return violationCount > 0 ? new Finding(id, title(name), severity, violationCount) : null;
    }

    private static Map<String, Object> database(
            String name, String status, String message, boolean truncated, List<Map<String, Object>> sections) {
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("name", name);
        database.put("status", status);
        database.put("message", message);
        database.put("truncated", truncated);
        database.put("sections", sections);
        return database;
    }

    private static Map<String, Object> section(
            String id, String status, String reason, int rowCount, boolean truncated) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("id", id);
        section.put("status", status);
        section.put("reason", reason);
        section.put("rowCount", rowCount);
        section.put("truncated", truncated);
        return section;
    }

    private static String coordinates(DependencyDto dependency) {
        String coordinates =
                dependency.groupId() == null || dependency.groupId().isBlank()
                        ? String.valueOf(dependency.packageName())
                        : dependency.groupId() + ":" + dependency.artifactId();
        return dependency.version() == null ? coordinates : coordinates + ":" + dependency.version();
    }

    /** The most severe advisory that is not dismissed, as its id and summary; the first one when all are dismissed. */
    private static String mostSevereAdvisory(List<DependencyVulnerabilityDto> vulnerabilities) {
        DependencyVulnerabilityDto chosen = null;
        for (DependencyVulnerabilityDto vulnerability : vulnerabilities) {
            if (chosen == null
                    || (chosen.dismissed() && !vulnerability.dismissed())
                    || (chosen.dismissed() == vulnerability.dismissed()
                            && SeverityOrder.rank(VULNERABILITY_SEVERITIES, vulnerability.severity())
                                    < SeverityOrder.rank(VULNERABILITY_SEVERITIES, chosen.severity()))) {
                chosen = vulnerability;
            }
        }
        if (chosen == null) {
            return null;
        }
        String summary = chosen.summary() == null || chosen.summary().isBlank() ? "" : ": " + chosen.summary();
        return title(chosen.id() + summary);
    }

    private static String title(String text) {
        if (text == null || text.length() <= MAX_TITLE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }
}
