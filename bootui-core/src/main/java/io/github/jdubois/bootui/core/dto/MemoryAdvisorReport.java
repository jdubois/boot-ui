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
public record MemoryAdvisorReport(
        boolean localOnly,
        String disclaimer,
        int rulesEvaluated,
        int violationsFound,
        MemoryAdvisorSummaryDto summary,
        List<MemoryAdvisorSeverityCountDto> severityCounts,
        MemoryAdvisorScanStatusDto scan,
        List<MemoryAdvisorRuleResultDto> results) {}
