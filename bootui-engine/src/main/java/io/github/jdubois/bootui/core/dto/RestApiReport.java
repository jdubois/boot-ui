package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local REST API Advisor panel. The results list contains violating rules
 * only, ordered by severity and impact.
 */
public record RestApiReport(
        boolean localOnly,
        String disclaimer,
        List<String> basePackages,
        int controllersAnalyzed,
        int handlersAnalyzed,
        int rulesEvaluated,
        int violationsFound,
        List<RestApiSeverityCountDto> severityCounts,
        RestApiScanStatusDto scan,
        List<RestApiRuleResultDto> results,
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public RestApiReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        basePackages = DtoCollections.immutableCopy(basePackages);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
    }

    public RestApiReport(
            boolean localOnly,
            String disclaimer,
            List<String> basePackages,
            int controllersAnalyzed,
            int handlersAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            List<RestApiSeverityCountDto> severityCounts,
            RestApiScanStatusDto scan,
            List<RestApiRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
        this(
                localOnly,
                disclaimer,
                basePackages,
                controllersAnalyzed,
                handlersAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                evidence,
                null);
    }

    public RestApiReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new RestApiReport(
                localOnly,
                disclaimer,
                basePackages,
                controllersAnalyzed,
                handlersAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                evidence,
                violationDetails);
    }
}
