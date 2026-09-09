package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Spring Advisor panel. The results list contains violating checks only,
 * ordered by severity and impact.
 */
public record SpringReport(
        boolean localOnly,
        String disclaimer,
        List<String> inspected,
        int componentsAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<SpringSeverityCountDto> severityCounts,
        SpringScanStatusDto scan,
        List<SpringRuleResultDto> results,
        List<SpringRuleResultDto> analysisErrors,
        AdvisorEvidenceDto evidence) {

    public SpringReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        inspected = DtoCollections.immutableCopy(inspected);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        analysisErrors = DtoCollections.immutableCopy(analysisErrors);
    }
}
