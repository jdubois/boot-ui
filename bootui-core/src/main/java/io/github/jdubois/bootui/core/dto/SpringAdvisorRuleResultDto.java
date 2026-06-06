package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Spring Advisor rule evaluated against the host application's runtime context.
 */
public record SpringAdvisorRuleResultDto(
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
