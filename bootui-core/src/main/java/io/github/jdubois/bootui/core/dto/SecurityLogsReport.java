package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for Spring Boot audit/security events.
 */
public record SecurityLogsReport(
        boolean auditEventsPresent,
        String unavailableReason,
        int maxLogs,
        List<SecurityLogTypeSummaryDto> typeSummaries,
        List<SecurityLogEventDto> events,
        PageMetadata page) {

    public static SecurityLogsReport unavailable(String reason, int maxLogs) {
        return new SecurityLogsReport(
                false, reason, maxLogs, List.of(), List.of(), new PageMetadata(0, 0, 0, 0, 0, false));
    }
}
