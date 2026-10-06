package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Exceptions panel: the grouped exceptions captured so far plus the
 * configured retention bound.
 *
 * @param caughtInCode the summary of exceptions application code caught, from the BootUI agent's
 *     {@code caught-exceptions} sensor ({@code docs/PLAN-v2.md} M5-6), or {@code null} when the sensor does not
 *     record in this run
 */
public record ExceptionsReport(
        boolean available,
        String unavailableReason,
        int maxGroups,
        long totalExceptions,
        List<ExceptionGroupDto> groups,
        CaughtExceptionsSummaryDto caughtInCode) {

    public ExceptionsReport {
        groups = DtoCollections.immutableCopy(groups);
    }

    /** A report without the caught-in-code summary. */
    public ExceptionsReport(
            boolean available,
            String unavailableReason,
            int maxGroups,
            long totalExceptions,
            List<ExceptionGroupDto> groups) {
        this(available, unavailableReason, maxGroups, totalExceptions, groups, null);
    }

    public static ExceptionsReport unavailable(String reason, int maxGroups) {
        return new ExceptionsReport(false, reason, maxGroups, 0L, List.of());
    }

    /** This report with {@code summary} as its caught-in-code summary. */
    public ExceptionsReport withCaughtInCode(CaughtExceptionsSummaryDto summary) {
        return new ExceptionsReport(available, unavailableReason, maxGroups, totalExceptions, groups, summary);
    }
}
