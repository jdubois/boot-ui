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
        AdvisorEvidenceDto evidence) {

    public RestApiReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        basePackages = DtoCollections.immutableCopy(basePackages);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
    }
}
