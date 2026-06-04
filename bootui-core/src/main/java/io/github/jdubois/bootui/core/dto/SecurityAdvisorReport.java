package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Spring Security Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record SecurityAdvisorReport(
        boolean localOnly,
        String disclaimer,
        List<String> filterChains,
        int filterChainsAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<SecurityAdvisorSeverityCountDto> severityCounts,
        SecurityAdvisorScanStatusDto scan,
        List<SecurityAdvisorRuleResultDto> results) {}
