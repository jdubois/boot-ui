package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level transaction report returned by the Transactions panel.
 *
 * @param available whether transaction boundary capture is wired and active
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param capturing whether new transactions are currently being recorded (runtime pause/resume)
 * @param bufferSize maximum number of transactions retained in memory
 * @param totalCaptured total transactions seen since startup (may exceed buffer)
 * @param slowTransactionThresholdMillis threshold above which a transaction is "slow"
 * @param connectionHoldThresholdMillis threshold above which a transaction is flagged as holding a
 *     connection too long
 * @param stats aggregate counters over the retained buffer
 * @param entries the retained transactions, most recently completed first; each entry's {@code
 *     parentId} identifies its enclosing transaction so the panel can render a call tree
 * @param warnings non-fatal advisories about the current capture state
 */
public record TransactionReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        int bufferSize,
        long totalCaptured,
        long slowTransactionThresholdMillis,
        long connectionHoldThresholdMillis,
        TransactionStatsDto stats,
        List<TransactionEntryDto> entries,
        List<String> warnings) {

    public TransactionReport {
        entries = entries == null ? List.of() : List.copyOf(entries);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static TransactionReport unavailable(String reason) {
        return new TransactionReport(
                false, reason, false, 0, 0, 0, 0, TransactionStatsDto.empty(), List.of(), List.of());
    }
}
