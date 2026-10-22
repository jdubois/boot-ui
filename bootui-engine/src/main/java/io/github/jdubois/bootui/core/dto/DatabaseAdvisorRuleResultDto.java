package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Database Advisor rule evaluated against the host application's physical schema
 * (and, where available, its mapped Hibernate entities).
 *
 * @param suppressedCount findings of this rule the developer dismissed one by one; {@code violationCount} and the
 *     samples describe the remaining, active findings, and {@code violationCount + suppressedCount} is the scan's total
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
        boolean dismissed,
        int suppressedCount) {

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
            String learnMoreUrl,
            boolean dismissed) {
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
                dismissed,
                0);
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
                false,
                0);
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
                dismissed,
                suppressedCount);
    }

    /**
     * This result as the report shows it once {@code suppressedCount} findings are dismissed one by one: the active
     * count and samples replace the scan's.
     */
    public DatabaseAdvisorRuleResultDto withSuppression(
            int suppressedCount, int violationCount, List<String> sampleViolations) {
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
                dismissed,
                suppressedCount);
    }
}
