package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Hibernate Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record HibernateAdvisorReport(
        boolean localOnly,
        String disclaimer,
        List<String> entityPackages,
        int entitiesAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<HibernateAdvisorSeverityCountDto> severityCounts,
        HibernateAdvisorScanStatusDto scan,
        List<HibernateAdvisorRuleResultDto> results) {}
