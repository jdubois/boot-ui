package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local architecture (ArchUnit) hygiene panel. The results list contains
 * violating rules only, ordered by severity and impact.
 */
public record ArchitectureReport(
        boolean localOnly,
        String disclaimer,
        List<String> basePackages,
        int classesAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<ArchitectureSeverityCountDto> severityCounts,
        ArchitectureScanStatusDto scan,
        List<ArchitectureRuleResultDto> results,
        List<ArchitectureRuleResultDto> analysisErrors,
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public ArchitectureReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        basePackages = DtoCollections.immutableCopy(basePackages);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        analysisErrors = DtoCollections.immutableCopy(analysisErrors);
    }

    public ArchitectureReport(
            boolean localOnly,
            String disclaimer,
            List<String> basePackages,
            int classesAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            List<ArchitectureSeverityCountDto> severityCounts,
            ArchitectureScanStatusDto scan,
            List<ArchitectureRuleResultDto> results,
            List<ArchitectureRuleResultDto> analysisErrors,
            AdvisorEvidenceDto evidence) {
        this(
                localOnly,
                disclaimer,
                basePackages,
                classesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                null);
    }

    public ArchitectureReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new ArchitectureReport(
                localOnly,
                disclaimer,
                basePackages,
                classesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                violationDetails);
    }
}
