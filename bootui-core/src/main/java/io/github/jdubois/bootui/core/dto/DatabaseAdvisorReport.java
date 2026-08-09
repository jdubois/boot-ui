package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Database Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record DatabaseAdvisorReport(
        boolean localOnly,
        String disclaimer,
        List<String> dataSourceNames,
        int tablesAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<DatabaseAdvisorSeverityCountDto> severityCounts,
        DatabaseAdvisorScanStatusDto scan,
        List<DatabaseAdvisorRuleResultDto> results) {}
