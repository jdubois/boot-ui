package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Spring Security Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record SecurityReport(
        boolean localOnly,
        String disclaimer,
        List<String> filterChains,
        int filterChainsAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<SecuritySeverityCountDto> severityCounts,
        SecurityScanStatusDto scan,
        List<SecurityRuleResultDto> results) {}
