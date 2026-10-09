package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local Memory Advisor panel. The results list contains violating
 * checks only, ordered by severity and impact.
 *
 * <p>The advisor reuses the JVM data already surfaced by the Memory, Threads, and Heap Dump
 * panels and evaluates a bounded, static ruleset that produces health findings with severities
 * (heap pressure, memory pools, GC configuration, threads, heap content, and class loading).</p>
 */
public record MemoryReport(
        boolean localOnly,
        String disclaimer,
        int rulesEvaluated,
        int violationsFound,
        MemorySummaryDto summary,
        List<MemorySeverityCountDto> severityCounts,
        MemoryScanStatusDto scan,
        List<MemoryRuleResultDto> results,
        List<MemoryRuleResultDto> analysisErrors,
        AdvisorEvidenceDto evidence,
        AdvisorViolationDetailsDto violationDetails) {

    public MemoryReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        violationDetails = violationDetails == null ? AdvisorViolationDetailsDto.unknown() : violationDetails;
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        results = DtoCollections.immutableCopy(results);
        analysisErrors = DtoCollections.immutableCopy(analysisErrors);
    }

    public MemoryReport(
            boolean localOnly,
            String disclaimer,
            int rulesEvaluated,
            int violationsFound,
            MemorySummaryDto summary,
            List<MemorySeverityCountDto> severityCounts,
            MemoryScanStatusDto scan,
            List<MemoryRuleResultDto> results,
            List<MemoryRuleResultDto> analysisErrors,
            AdvisorEvidenceDto evidence) {
        this(
                localOnly,
                disclaimer,
                rulesEvaluated,
                violationsFound,
                summary,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                null);
    }

    public MemoryReport withViolationDetails(AdvisorViolationDetailsDto violationDetails) {
        return new MemoryReport(
                localOnly,
                disclaimer,
                rulesEvaluated,
                violationsFound,
                summary,
                severityCounts,
                scan,
                results,
                analysisErrors,
                evidence,
                violationDetails);
    }
}
