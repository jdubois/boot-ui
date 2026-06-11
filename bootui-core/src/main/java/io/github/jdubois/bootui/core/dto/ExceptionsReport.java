package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Exceptions panel: the grouped exceptions captured so far plus the
 * configured retention bound.
 */
public record ExceptionsReport(
        boolean available,
        String unavailableReason,
        int maxGroups,
        long totalExceptions,
        List<ExceptionGroupDto> groups) {

    public static ExceptionsReport unavailable(String reason, int maxGroups) {
        return new ExceptionsReport(false, reason, maxGroups, 0L, List.of());
    }
}
