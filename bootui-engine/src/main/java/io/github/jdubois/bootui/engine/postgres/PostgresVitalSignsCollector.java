package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;

/**
 * Reads the database's own vital signs from {@code pg_stat_database}, {@code pg_database} and
 * {@code pg_stat_activity}.
 *
 * <p>The session half is read separately from the counter half on purpose. A role without {@code pg_monitor}
 * still sees its own sessions but not everyone else's, so a session read that fails or under-reports must not
 * discard the cache, rollback and wraparound numbers that were read correctly: the section reports itself as
 * partially read instead.</p>
 */
final class PostgresVitalSignsCollector implements PostgresCollector {

    private static final String DATABASE_SQL = """
            select current_database() as database_name,
                   pg_database_size(current_database()) as database_size,
                   d.xact_commit as xact_commit,
                   d.xact_rollback as xact_rollback,
                   d.blks_read as blks_read,
                   d.blks_hit as blks_hit,
                   d.deadlocks as deadlocks,
                   d.temp_files as temp_files,
                   d.temp_bytes as temp_bytes,
                   age(p.datfrozenxid) as xid_age,
                   (select setting::bigint from pg_settings where name = 'autovacuum_freeze_max_age')
                       as freeze_max_age,
                   (select setting::int from pg_settings where name = 'max_connections') as max_connections
            from pg_stat_database d
            join pg_database p on p.oid = d.datid
            where d.datname = current_database()
            """;

    private static final String ACTIVITY_SQL = """
            select (count(*))::int as sessions,
                   (count(*) filter (where state = 'active'))::int as active_sessions,
                   (count(*) filter (where state like 'idle in transaction%'))::int as idle_in_transaction,
                   (count(*) filter (where wait_event_type = 'Lock'))::int as blocked_sessions,
                   coalesce(max(extract(epoch from (now() - xact_start))), 0) as longest_transaction_seconds
            from pg_stat_activity
            where datname = current_database()
            """;

    @Override
    public String id() {
        return PostgresSectionIds.VITAL_SIGNS;
    }

    @Override
    public String title() {
        return "Vital signs";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<DatabaseCounters> counters = PostgresQuery.readOne(
                context,
                "Database statistics",
                DATABASE_SQL,
                resultSet -> new DatabaseCounters(
                        resultSet.getString("database_name"),
                        PostgresQuery.longOrNull(resultSet, "database_size"),
                        PostgresQuery.longOrNull(resultSet, "xact_commit"),
                        PostgresQuery.longOrNull(resultSet, "xact_rollback"),
                        PostgresQuery.longOrNull(resultSet, "blks_read"),
                        PostgresQuery.longOrNull(resultSet, "blks_hit"),
                        PostgresQuery.longOrNull(resultSet, "deadlocks"),
                        PostgresQuery.longOrNull(resultSet, "temp_files"),
                        PostgresQuery.longOrNull(resultSet, "temp_bytes"),
                        PostgresQuery.longOrNull(resultSet, "xid_age"),
                        PostgresQuery.longOrNull(resultSet, "freeze_max_age"),
                        PostgresQuery.intOrNull(resultSet, "max_connections")));
        if (!counters.available()) {
            return failed(counters.reason());
        }
        if (counters.empty()) {
            return skipped(
                    "pg_stat_database reported no row for the current database.",
                    "Grant the BootUI role membership of pg_monitor so it can read the database statistics views.");
        }
        DatabaseCounters row = counters.rows().get(0);

        PostgresRows<SessionCounters> sessions = PostgresQuery.readOne(
                context,
                "Session activity",
                ACTIVITY_SQL,
                resultSet -> new SessionCounters(
                        PostgresQuery.intOrNull(resultSet, "sessions"),
                        PostgresQuery.intOrNull(resultSet, "active_sessions"),
                        PostgresQuery.intOrNull(resultSet, "idle_in_transaction"),
                        PostgresQuery.intOrNull(resultSet, "blocked_sessions"),
                        PostgresQuery.doubleOrNull(resultSet, "longest_transaction_seconds")));
        SessionCounters session = sessions.available() && !sessions.empty()
                ? sessions.rows().get(0)
                : new SessionCounters(null, null, null, null, null);

        data.vitalSigns(new PostgresVitalSignsDto(
                row.databaseName(),
                ratio(row.blocksHit(), sum(row.blocksHit(), row.blocksRead())),
                ratio(row.rolledBack(), sum(row.committed(), row.rolledBack())),
                row.committed(),
                row.rolledBack(),
                session.sessions(),
                row.maxConnections(),
                ratio(toLong(session.sessions()), toLong(row.maxConnections())),
                session.activeSessions(),
                session.idleInTransaction(),
                session.longestTransactionSeconds(),
                session.blockedSessions(),
                row.transactionIdAge(),
                row.freezeMaxAge(),
                ratio(row.transactionIdAge(), row.freezeMaxAge()),
                row.databaseSize(),
                row.deadlocks(),
                row.temporaryFiles(),
                row.temporaryBytes()));
        if (!sessions.available()) {
            return partial(1, sessions.reason());
        }
        return available(1, false);
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static Long sum(Long first, Long second) {
        if (first == null && second == null) {
            return null;
        }
        return (first == null ? 0L : first) + (second == null ? 0L : second);
    }

    private static Double ratio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return null;
        }
        return numerator.doubleValue() / denominator.doubleValue();
    }

    private record DatabaseCounters(
            String databaseName,
            Long databaseSize,
            Long committed,
            Long rolledBack,
            Long blocksRead,
            Long blocksHit,
            Long deadlocks,
            Long temporaryFiles,
            Long temporaryBytes,
            Long transactionIdAge,
            Long freezeMaxAge,
            Integer maxConnections) {}

    private record SessionCounters(
            Integer sessions,
            Integer activeSessions,
            Integer idleInTransaction,
            Integer blockedSessions,
            Double longestTransactionSeconds) {}
}
