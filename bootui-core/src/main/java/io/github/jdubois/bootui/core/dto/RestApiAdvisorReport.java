package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local REST API Advisor panel. The results list contains violating rules
 * only, ordered by severity and impact.
 */
public record RestApiAdvisorReport(
        boolean localOnly,
        String disclaimer,
        List<String> basePackages,
        int controllersAnalyzed,
        int handlersAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<RestApiAdvisorSeverityCountDto> severityCounts,
        RestApiAdvisorScanStatusDto scan,
        List<RestApiAdvisorRuleResultDto> results) {}
