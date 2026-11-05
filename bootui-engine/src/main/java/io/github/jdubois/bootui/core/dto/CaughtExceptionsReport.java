package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Exceptions panel's <b>Caught in application code</b> section ({@code docs/PLAN-v2.md} M5-6): the handlers of
 * application code the BootUI agent saw catch exceptions, and what became of them ({@link CaughtExceptionRowDto}).
 * Findings come first, then insufficient rows, partial rows, and remaining rows by occurrences.
 *
 * @param available whether the agent's {@code caught-exceptions} sensor records in this run
 * @param unavailableReason why not, when not available
 * @param limitations what the evidence cannot show in this run, such as a logger that bypasses BootUI's appender
 * @param settling occurrences whose request has not settled yet, counted as {@code pending}
 * @param occurrences every occurrence in the rows
 * @param findings the rows that are findings
 * @param insufficient rows below the finding threshold with complete evidence
 * @param partial rows with unknown, pending, or counted outcomes that prevent a verdict
 * @param rows the rows, bounded
 */
public record CaughtExceptionsReport(
        boolean available,
        String unavailableReason,
        List<String> limitations,
        long settling,
        long occurrences,
        int findings,
        int insufficient,
        int partial,
        List<CaughtExceptionRowDto> rows) {

    public CaughtExceptionsReport {
        limitations = DtoCollections.immutableCopy(limitations);
        rows = DtoCollections.immutableCopy(rows);
    }

    public CaughtExceptionsReport(
            boolean available,
            String unavailableReason,
            List<String> limitations,
            long settling,
            long occurrences,
            int findings,
            List<CaughtExceptionRowDto> rows) {
        this(available, unavailableReason, limitations, settling, occurrences, findings, 0, 0, rows);
    }

    public static CaughtExceptionsReport unavailable(String reason) {
        return new CaughtExceptionsReport(false, reason, List.of(), 0L, 0L, 0, 0, 0, List.of());
    }
}
