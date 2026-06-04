package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level HTTP Sessions panel response.
 */
public record HttpSessionsReport(
        boolean available,
        String unavailableReason,
        int totalSessions,
        int returnedSessions,
        int limit,
        boolean limited,
        boolean actionEnabled,
        String valueExposure,
        List<HttpSessionDto> sessions) {

    public static HttpSessionsReport unavailable(
            String reason, int limit, boolean actionEnabled, String valueExposure) {
        return new HttpSessionsReport(false, reason, 0, 0, limit, false, actionEnabled, valueExposure, List.of());
    }
}
