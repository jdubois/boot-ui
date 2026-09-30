package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Hibernate Advisor rule evaluated against mapped application entities.
 *
 * @param coverageNote {@code null} when the rule was fully evaluated; otherwise which units were only partly
 *     evaluated and why, so a violation reported from the evaluated part is not mistaken for full coverage
 */
public record HibernateRuleResultDto(
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
        String coverageNote,
        List<AdvisorViolationLocationDto> sampleLocations) {

    public HibernateRuleResultDto {
        sampleViolations = DtoCollections.immutableCopy(sampleViolations);
        sampleLocations = DtoCollections.alignedCopy(sampleViolations, sampleLocations);
    }

    public HibernateRuleResultDto(
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
            String coverageNote) {
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
                coverageNote,
                List.of());
    }

    public HibernateRuleResultDto(
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
                null);
    }

    public HibernateRuleResultDto(
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
                null);
    }

    public HibernateRuleResultDto withDismissed(boolean dismissed) {
        return new HibernateRuleResultDto(
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
                coverageNote,
                sampleLocations);
    }

    public HibernateRuleResultDto withCoverageNote(String coverageNote) {
        return new HibernateRuleResultDto(
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
                coverageNote,
                sampleLocations);
    }

    /** This result with locations aligned index-for-index with {@code sampleViolations}, or none when empty. */
    public HibernateRuleResultDto withSampleLocations(List<AdvisorViolationLocationDto> sampleLocations) {
        return new HibernateRuleResultDto(
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
                coverageNote,
                sampleLocations);
    }
}
