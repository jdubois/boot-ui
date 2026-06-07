package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one REST API Advisor rule evaluated against the host application's web layer.
 */
public record RestApiRuleResultDto(
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
