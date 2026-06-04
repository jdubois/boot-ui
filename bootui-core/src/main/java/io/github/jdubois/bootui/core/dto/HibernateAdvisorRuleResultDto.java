package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Hibernate Advisor rule evaluated against mapped application entities.
 */
public record HibernateAdvisorRuleResultDto(
        String id,
        String name,
        String category,
        String severity,
        String description,
        String status,
        int violationCount,
        List<String> sampleViolations,
        String recommendation,
        String learnMoreUrl) {}
