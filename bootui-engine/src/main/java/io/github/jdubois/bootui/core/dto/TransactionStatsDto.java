package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate counters over the transactions currently retained in the Transactions panel buffer.
 *
 * @param totalTransactions number of captured transactions in the buffer
 * @param totalDurationMillis sum of transaction durations across the buffer
 * @param maxDurationMillis longest single transaction duration
 * @param avgDurationMillis mean transaction duration
 * @param slowTransactions transactions over the slow-transaction threshold
 * @param connectionHeldTransactions transactions over the connection-hold threshold
 * @param committedCount transactions that completed with {@code COMMITTED} status
 * @param rolledBackCount transactions that completed with {@code ROLLED_BACK} status
 * @param unknownCount transactions whose outcome could not be observed (including failed begins)
 * @param nestedCount transactions with a non-null {@code parentId}
 * @param evicted transactions dropped from the buffer because it reached capacity
 */
public record TransactionStatsDto(
        long totalTransactions,
        long totalDurationMillis,
        long maxDurationMillis,
        double avgDurationMillis,
        long slowTransactions,
        long connectionHeldTransactions,
        long committedCount,
        long rolledBackCount,
        long unknownCount,
        long nestedCount,
        long evicted) {

    public static TransactionStatsDto empty() {
        return new TransactionStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
