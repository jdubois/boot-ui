package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Spring Security Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 */
public record SecurityReport(
        boolean localOnly,
        String disclaimer,
        List<String> filterChains,
        int filterChainsAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<SecuritySeverityCountDto> severityCounts,
        SecurityScanStatusDto scan,
        List<SecurityRuleResultDto> results,
        List<SecurityRuleResultDto> analysisErrors,
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public SecurityReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        filterChains = DtoCollections.immutableCopy(filterChains);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        analysisErrors = DtoCollections.immutableCopy(analysisErrors);
    }

    public SecurityReport(
            boolean localOnly,
            String disclaimer,
            List<String> filterChains,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            List<SecuritySeverityCountDto> severityCounts,
            SecurityScanStatusDto scan,
            List<SecurityRuleResultDto> results,
            List<SecurityRuleResultDto> analysisErrors,
            AdvisorEvidenceDto evidence) {
        this(
                localOnly,
                disclaimer,
                filterChains,
                filterChainsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                null);
    }

    public SecurityReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new SecurityReport(
                localOnly,
                disclaimer,
                filterChains,
                filterChainsAnalyzed,
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
