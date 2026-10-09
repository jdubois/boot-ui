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
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public SpringReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        inspected = DtoCollections.immutableCopy(inspected);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        analysisErrors = DtoCollections.immutableCopy(analysisErrors);
    }

    public SpringReport(
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
        this(
                localOnly,
                disclaimer,
                inspected,
                componentsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                null);
    }

    public SpringReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new SpringReport(
                localOnly,
                disclaimer,
                inspected,
                componentsAnalyzed,
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
