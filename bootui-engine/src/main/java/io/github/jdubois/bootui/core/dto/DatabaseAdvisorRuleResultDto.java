package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Database Advisor rule evaluated against the host application's physical schema
 * (and, where available, its mapped Hibernate entities).
 */
public record DatabaseAdvisorRuleResultDto(
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

    public DatabaseAdvisorRuleResultDto {
        sampleViolations = DtoCollections.immutableCopy(sampleViolations);
    }

    public DatabaseAdvisorRuleResultDto(
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

    public DatabaseAdvisorRuleResultDto withDismissed(boolean dismissed) {
        return new DatabaseAdvisorRuleResultDto(
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
