package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresReplicaDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import java.util.List;

/**
 * Reads replication, checkpoint and WAL basics.
 *
 * <p>Three things are gated. Replica lag is measured against {@code pg_current_wal_lsn()}, which only exists
 * on a primary, so it is skipped entirely in recovery. PostgreSQL 17 moved the checkpoint counters out of
 * {@code pg_stat_bgwriter} into {@code pg_stat_checkpointer}, so the view is chosen from the server version.
 * Replication slots need a privilege a bare application role usually lacks, so an unreadable slot count
 * degrades the section instead of failing it.</p>
 */
final class PostgresReplicationCollector implements PostgresCollector {

    private static final String RECOVERY_SQL = "select pg_is_in_recovery() as in_recovery";

    private static final String REPLICAS_SQL = """
            select application_name, client_addr::text as client_addr, state, sync_state,
                   pg_wal_lsn_diff(pg_current_wal_lsn(), sent_lsn) as sent_lag,
                   pg_wal_lsn_diff(pg_current_wal_lsn(), flush_lsn) as flush_lag,
                   pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) as replay_lag
            from pg_stat_replication
            order by application_name
            limit ?
            """;

    private static final String SLOTS_SQL = """
            select (count(*))::int as slots,
                   (count(*) filter (where not active))::int as inactive_slots
            from pg_replication_slots
            """;

    @Override
    public String id() {
        return PostgresSectionIds.REPLICATION;
    }

    @Override
    public String title() {
        return "Replication, checkpoints and WAL";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<Boolean> recovery = PostgresQuery.readOne(
                context, "Recovery state", RECOVERY_SQL, resultSet -> resultSet.getBoolean("in_recovery"));
        if (!recovery.available()) {
            return failed(recovery.reason());
        }
        boolean inRecovery =
                !recovery.empty() && Boolean.TRUE.equals(recovery.rows().get(0));

        List<PostgresReplicaDto> replicas = List.of();
        String limitation = null;
        if (!inRecovery) {
            PostgresRows<PostgresReplicaDto> rows = PostgresQuery.readList(
                    context,
                    "Replication statistics",
                    REPLICAS_SQL,
                    context.limits().maxReplicas(),
                    resultSet -> new PostgresReplicaDto(
                            resultSet.getString("application_name"),
                            resultSet.getString("client_addr"),
                            resultSet.getString("state"),
                            resultSet.getString("sync_state"),
                            PostgresQuery.longOrNull(resultSet, "sent_lag"),
                            PostgresQuery.longOrNull(resultSet, "flush_lag"),
                            PostgresQuery.longOrNull(resultSet, "replay_lag")));
            if (rows.available()) {
                replicas = rows.rows();
            } else {
                limitation = rows.reason();
            }
        } else {
            limitation = "This server is a standby, so replica lag is not measurable from here.";
        }

        Checkpoints checkpoints = readCheckpoints(context);
        if (checkpoints == null) {
            limitation = limitation == null ? "The checkpoint counters could not be read." : limitation;
            checkpoints = new Checkpoints(null, null, null);
        }

        PostgresRows<Slots> slots = PostgresQuery.readOne(
                context,
                "Replication slots",
                SLOTS_SQL,
                resultSet -> new Slots(
                        PostgresQuery.longOrNull(resultSet, "slots"),
                        PostgresQuery.longOrNull(resultSet, "inactive_slots")));
        Slots slotCounts = slots.available() && !slots.empty() ? slots.rows().get(0) : new Slots(null, null);
        if (!slots.available() && limitation == null) {
            limitation = slots.reason();
        }

        data.replication(new PostgresReplicationDto(
                inRecovery,
                replicas,
                checkpoints.timed(),
                checkpoints.requested(),
                checkpoints.writeSeconds(),
                slotCounts.slots(),
                slotCounts.inactiveSlots(),
                data.setting("wal_level")));
        int rowCount = replicas.size();
        return limitation == null ? available(rowCount, false) : partial(rowCount, limitation);
    }

    /** PostgreSQL 17 moved the checkpoint counters from {@code pg_stat_bgwriter} to {@code pg_stat_checkpointer}. */
    static String checkpointSql(PostgresReadContext context) {
        if (context.atLeast(17)) {
            return "select num_timed as checkpoints_timed, num_requested as checkpoints_requested,"
                    + " write_time as write_time from pg_stat_checkpointer";
        }
        return "select checkpoints_timed as checkpoints_timed, checkpoints_req as checkpoints_requested,"
                + " checkpoint_write_time as write_time from pg_stat_bgwriter";
    }

    private Checkpoints readCheckpoints(PostgresReadContext context) {
        PostgresRows<Checkpoints> rows =
                PostgresQuery.readOne(context, "Checkpoint statistics", checkpointSql(context), resultSet -> {
                    Double writeMillis = PostgresQuery.doubleOrNull(resultSet, "write_time");
                    return new Checkpoints(
                            PostgresQuery.longOrNull(resultSet, "checkpoints_timed"),
                            PostgresQuery.longOrNull(resultSet, "checkpoints_requested"),
                            writeMillis == null ? null : writeMillis / 1000d);
                });
        return rows.available() && !rows.empty() ? rows.rows().get(0) : null;
    }

    private record Checkpoints(Long timed, Long requested, Double writeSeconds) {}

    private record Slots(Long slots, Long inactiveSlots) {}
}
