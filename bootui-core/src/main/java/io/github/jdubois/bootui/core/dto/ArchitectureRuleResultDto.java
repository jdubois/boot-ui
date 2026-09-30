package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one architecture rule violation evaluated against the host application classes.
 */
public record ArchitectureRuleResultDto(
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
        List<AdvisorViolationLocationDto> sampleLocations) {

    public ArchitectureRuleResultDto {
        sampleViolations = DtoCollections.immutableCopy(sampleViolations);
        sampleLocations = DtoCollections.alignedCopy(sampleViolations, sampleLocations);
    }

    public ArchitectureRuleResultDto(
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
                List.of());
    }

    public ArchitectureRuleResultDto(
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

    public ArchitectureRuleResultDto withDismissed(boolean dismissed) {
        return new ArchitectureRuleResultDto(
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
                sampleLocations);
    }

    /** This result with locations aligned index-for-index with {@code sampleViolations}, or none when empty. */
    public ArchitectureRuleResultDto withSampleLocations(List<AdvisorViolationLocationDto> sampleLocations) {
        return new ArchitectureRuleResultDto(
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
                sampleLocations);
    }
}
