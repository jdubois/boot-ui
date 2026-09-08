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
        AdvisorEvidenceDto evidence) {

    public ArchitectureReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
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
            List<ArchitectureRuleResultDto> analysisErrors) {
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
                AdvisorEvidenceDto.unknown());
    }
}
