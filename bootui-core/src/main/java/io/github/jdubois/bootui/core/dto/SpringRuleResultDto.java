package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Spring Advisor rule evaluated against the host application's runtime context.
 */
public record SpringRuleResultDto(
        String id,
        String name,
        String category,
        String severity,
        String description,
        String status,
        int violationCount,
        List<String> sampleViolations,
        String recommendation,
        String learnMoreUrl,
        boolean dismissed) {

    public SpringRuleResultDto(
            String id,
            String name,
            String category,
            String severity,
            String description,
            String status,
            int violationCount,
            List<String> sampleViolations,
            String recommendation,
            String learnMoreUrl) {
        this(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                learnMoreUrl,
                false);
    }

    public SpringRuleResultDto withDismissed(boolean dismissed) {
        return new SpringRuleResultDto(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                learnMoreUrl,
                dismissed);
    }

    /**
     * Returns a copy pointing at a different "learn more" link. Used for the handful of rules whose
     * underlying Spring Boot documentation page differs between the servlet and reactive (WebFlux)
     * adapters even though the rule and its property key are shared.
     */
    public SpringRuleResultDto withLearnMoreUrl(String learnMoreUrl) {
        return new SpringRuleResultDto(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                learnMoreUrl,
                dismissed);
    }
}
