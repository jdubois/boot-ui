package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Aggregated Copilot dashboard payload. Counts are computed from sanitized
 * session metadata only; raw prompts, arguments, output, and diffs are never
 * included.
 */
public record CopilotDashboardDto(
        boolean available,
        String unavailableReason,
        String sessionStateDir,
        int sessionCount,
        int eventCount,
        int turnCount,
        int errorCount,
        int activeLast24Hours,
        int activeLast7Days,
        int sessionsWithSchemaDrift,
        Long lastActivityEpochMillis,
        List<CopilotMetricCount> categoryCounts,
        List<CopilotMetricCount> modelCounts,
        List<CopilotMetricCount> topTools,
        int otherToolEventCount,
        List<CopilotActivityBucket> activityBuckets,
        List<CopilotActivityBucket> dailyActivityBuckets,
        List<CopilotSessionSummary> recentSessions,
        List<String> warnings) {}
