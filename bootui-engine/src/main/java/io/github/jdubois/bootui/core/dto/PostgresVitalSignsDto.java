package io.github.jdubois.bootui.core.dto;

/**
 * The database's own vital signs, read from {@code pg_stat_database}, {@code pg_stat_activity} and
 * {@code pg_settings}.
 *
 * <p>Every component is nullable on purpose: a role that cannot see the state of other sessions still gets
 * real cache and transaction counters, and the panel says which numbers are missing instead of substituting
 * zero — a zero would read as "checked and clean".</p>
 *
 * @param databaseName the database the application is connected to
 * @param cacheHitRatio buffer-cache hit ratio in {@code [0,1]}, since the last statistics reset
 * @param rollbackRatio rolled-back share of all completed transactions, in {@code [0,1]}
 * @param connections client backends connected to the whole server, not only to this database, because
 *     {@code max_connections} is a cluster-wide ceiling
 * @param maxConnections the server's {@code max_connections} setting
 * @param connectionUsageRatio {@code connections / maxConnections}
 * @param activeSessions backends currently executing a statement; {@code null} when the role cannot see the
 *     state of other backends
 * @param idleInTransactionSessions backends holding an open transaction while idle, the classic lock hog
 * @param longestTransactionSeconds age of the oldest running transaction
 * @param blockedSessions backends currently waiting on a lock held by another backend
 * @param transactionIdAge {@code age(datfrozenxid)} for this database
 * @param wraparoundLimit {@code autovacuum_freeze_max_age}, the age at which anti-wraparound vacuum is forced
 * @param wraparoundUsageRatio {@code transactionIdAge / wraparoundLimit}
 */
public record PostgresVitalSignsDto(
        String databaseName,
        Double cacheHitRatio,
        Double rollbackRatio,
        Long transactionsCommitted,
        Long transactionsRolledBack,
        Integer connections,
        Integer maxConnections,
        Double connectionUsageRatio,
        Integer activeSessions,
        Integer idleInTransactionSessions,
        Double longestTransactionSeconds,
        Integer blockedSessions,
        Long transactionIdAge,
        Long wraparoundLimit,
        Double wraparoundUsageRatio,
        Long databaseSizeBytes,
        Long deadlocks,
        Long temporaryFiles,
        Long temporaryBytes) {}
