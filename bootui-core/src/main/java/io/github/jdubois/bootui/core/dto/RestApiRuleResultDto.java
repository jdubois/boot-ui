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
        String learnMoreUrl,
        boolean dismissed,
        List<AdvisorViolationLocationDto> sampleLocations) {

    public RestApiRuleResultDto {
        sampleViolations = DtoCollections.immutableCopy(sampleViolations);
        sampleLocations = DtoCollections.alignedCopy(sampleViolations, sampleLocations);
    }

    public RestApiRuleResultDto(
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

    public RestApiRuleResultDto(
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

    public RestApiRuleResultDto withDismissed(boolean dismissed) {
        return new RestApiRuleResultDto(
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
    public RestApiRuleResultDto withSampleLocations(List<AdvisorViolationLocationDto> sampleLocations) {
        return new RestApiRuleResultDto(
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
