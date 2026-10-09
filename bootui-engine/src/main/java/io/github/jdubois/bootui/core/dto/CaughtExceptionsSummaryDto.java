package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The summary of the Exceptions panel's <b>Caught in application code</b> section that the panel's report carries
 * ({@code docs/PLAN-v2.md} M5-6): counts and the top findings. The full section is {@link CaughtExceptionsReport}.
 *
 * @param available whether the agent's {@code caught-exceptions} sensor records in this run
 * @param unavailableReason why not, when not available
 * @param occurrences every occurrence observed
 * @param findings the rows that are findings
 * @param settling occurrences whose request has not settled yet
 * @param topFindings at most ten finding rows
 */
public record CaughtExceptionsSummaryDto(
        boolean available,
        String unavailableReason,
        long occurrences,
        int findings,
        long settling,
        List<CaughtExceptionRowDto> topFindings) {

    /** The most finding rows a summary carries. */
    public static final int MAX_TOP_FINDINGS = 10;

    public CaughtExceptionsSummaryDto {
        topFindings = DtoCollections.immutableCopy(topFindings);
    }

    /** The summary of {@code report}. */
    public static CaughtExceptionsSummaryDto of(CaughtExceptionsReport report) {
        return new CaughtExceptionsSummaryDto(
                report.available(),
                report.unavailableReason(),
                report.occurrences(),
                report.findings(),
                report.settling(),
                report.rows().stream()
                        .filter(CaughtExceptionRowDto::finding)
                        .limit(MAX_TOP_FINDINGS)
                        .toList());
    }
}
