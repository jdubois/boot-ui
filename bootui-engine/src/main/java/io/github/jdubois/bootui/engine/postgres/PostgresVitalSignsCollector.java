package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;

/**
 * Reads the database's own vital signs from {@code pg_stat_database}, {@code pg_database} and
 * {@code pg_stat_activity}.
 *
 * <p>The session half is read separately from the counter half on purpose. The connection total stays
 * <em>server-wide</em>, because {@code max_connections} is a cluster-wide ceiling that every database and
 * every background backend shares: comparing one database's sessions against it would quietly under-report a
 * server that is actually at its limit. The state breakdown, by contrast, describes this database's own
 * sessions, matching the Sessions table.</p>
 *
 * <p>A role without {@code pg_read_all_stats} does not see a degraded {@code pg_stat_activity}: PostgreSQL
 * removes the rows of backends it does not own, leaving nothing in the result set to notice. Counting the
 * survivors would manufacture a clean bill of health — "one session, active, nothing blocked" — so the whole
 * state breakdown is reported as unknown whenever the privilege probe says the role is restricted, and the
 * section is marked partially read. The counters that were read correctly — cache, transactions, wraparound —
 * are kept either way.</p>
 *
 * <p>For that reason the connection total is taken from {@code pg_stat_database.numbackends} summed across
 * the cluster rather than by counting {@code pg_stat_activity} rows: that sum is both server-wide, so it
 * pairs with {@code max_connections}, and reported in full to every role.</p>
 */
final class PostgresVitalSignsCollector implements PostgresCollector {

    private static final String DATABASE_SQL = """
            select current_database() as database_name,
                   pg_database_size(current_database()) as database_size,
                   d.xact_commit as xact_commit,
                   d.xact_rollback as xact_rollback,
                   d.blks_read as blks_read,
                   d.blks_hit as blks_hit,
                   (select sum(numbackends)::int from pg_stat_database) as backends,
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

    static final String RESTRICTED_SESSIONS_LIMITATION =
            "pg_stat_activity hides the state of backends this role does not own, so the session breakdown "
                    + "(active, idle in transaction, blocked, oldest transaction) is unknown. Grant the BootUI "
                    + "role membership of pg_monitor to read it.";

    static final String ACTIVITY_SQL = """
            select (count(*) filter (where state = 'active'))::int as active_sessions,
                   (count(*) filter (where state like 'idle in transaction%'))::int as idle_in_transaction,
                   (count(*) filter (where wait_event_type = 'Lock'))::int as blocked_sessions,
                   max(extract(epoch from (clock_timestamp() - xact_start))) as longest_transaction_seconds
            from pg_stat_activity
            where backend_type = 'client backend'
              and datname = current_database()
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
                        PostgresQuery.intOrNull(resultSet, "backends"),
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
                        PostgresQuery.intOrNull(resultSet, "active_sessions"),
                        PostgresQuery.intOrNull(resultSet, "idle_in_transaction"),
                        PostgresQuery.intOrNull(resultSet, "blocked_sessions"),
                        PostgresQuery.doubleOrNull(resultSet, "longest_transaction_seconds")));
        SessionCounters session =
                sessions.available() && !sessions.empty() ? sessions.rows().get(0) : SessionCounters.unknown();
        boolean restricted = data.statisticsRestricted();
        if (restricted) {
            session = SessionCounters.unknown();
        }

        data.vitalSigns(new PostgresVitalSignsDto(
                row.databaseName(),
                ratio(row.blocksHit(), sum(row.blocksHit(), row.blocksRead())),
                ratio(row.rolledBack(), sum(row.committed(), row.rolledBack())),
                row.committed(),
                row.rolledBack(),
                row.backends(),
                row.maxConnections(),
                ratio(toLong(row.backends()), toLong(row.maxConnections())),
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
            return partial(1, sessions.reason(), false);
        }
        if (restricted) {
            return partial(1, RESTRICTED_SESSIONS_LIMITATION, false);
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
            Integer backends,
            Long deadlocks,
            Long temporaryFiles,
            Long temporaryBytes,
            Long transactionIdAge,
            Long freezeMaxAge,
            Integer maxConnections) {}

    private record SessionCounters(
            Integer activeSessions,
            Integer idleInTransaction,
            Integer blockedSessions,
            Double longestTransactionSeconds) {

        static SessionCounters unknown() {
            return new SessionCounters(null, null, null, null);
        }
    }
}
