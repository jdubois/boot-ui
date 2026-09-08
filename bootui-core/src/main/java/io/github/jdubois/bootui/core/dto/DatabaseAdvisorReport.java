package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Database Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 *
 * <p>Everything the scan could <em>not</em> establish is reported separately from the findings:
 * {@link #dataSources()} carries each datasource's read status, {@link #diagnostics()} carries per-datasource,
 * per-table, per-augmentation and per-rule problems, and {@link #truncated()} says whether a scan bound cut
 * the analysis short. None of them count as violations, so an incomplete scan is visible without being
 * mistaken for a clean one — or for a failing one.</p>
 *
 * @param rulesSkipped rules that could not run (no datasource of that dialect, unreadable catalog data)
 * @param rulesErrored rules that failed while evaluating
 * @param truncated whether any scan bound (tables, columns, indexes, catalog rows, time) was reached
 */
public record DatabaseAdvisorReport(
        boolean localOnly,
        String disclaimer,
        List<String> dataSourceNames,
        List<DatabaseAdvisorDataSourceDto> dataSources,
        int tablesAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        int rulesSkipped,
        int rulesErrored,
        boolean truncated,
        List<DatabaseAdvisorSeverityCountDto> severityCounts,
        DatabaseAdvisorScanStatusDto scan,
        List<DatabaseAdvisorRuleResultDto> results,
        List<DatabaseAdvisorDiagnosticDto> diagnostics,
        AdvisorEvidenceDto evidence) {

    public DatabaseAdvisorReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        dataSourceNames = DtoCollections.immutableCopy(dataSourceNames);
        dataSources = DtoCollections.immutableCopy(dataSources);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        diagnostics = DtoCollections.immutableCopy(diagnostics);
    }

    public DatabaseAdvisorReport(
            boolean localOnly,
            String disclaimer,
            List<String> dataSourceNames,
            List<DatabaseAdvisorDataSourceDto> dataSources,
            int tablesAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            int rulesSkipped,
            int rulesErrored,
            boolean truncated,
            List<DatabaseAdvisorSeverityCountDto> severityCounts,
            DatabaseAdvisorScanStatusDto scan,
            List<DatabaseAdvisorRuleResultDto> results,
            List<DatabaseAdvisorDiagnosticDto> diagnostics) {
        this(
                localOnly,
                disclaimer,
                dataSourceNames,
                dataSources,
                tablesAnalyzed,
                rulesEvaluated,
                violationsFound,
                rulesSkipped,
                rulesErrored,
                truncated,
                severityCounts,
                scan,
                results,
                diagnostics,
                AdvisorEvidenceDto.unknown());
    }
}
