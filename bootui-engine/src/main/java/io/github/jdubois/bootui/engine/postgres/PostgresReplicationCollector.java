package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.PostgresReplicaDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads replication, checkpoint and WAL basics.
 *
 * <p>Three things are gated. Replica lag is measured against {@code pg_current_wal_lsn()}, which only exists
 * on a primary, so it is skipped entirely in recovery. PostgreSQL 17 moved the checkpoint counters out of
 * {@code pg_stat_bgwriter} into {@code pg_stat_checkpointer}, so the view is chosen by asking the catalog
 * which one exists rather than by trusting a server version the driver may not report.
 * Replication slots need a privilege a bare application role usually lacks, so an unreadable slot count
 * degrades the section instead of failing it.</p>
 *
 * <p>A replica's {@code client_addr} is a value, not metadata: it identifies a host on the operator's
 * network. It is therefore masked under {@link io.github.jdubois.bootui.core.ValueExposure#METADATA_ONLY},
 * the same policy that masks statement text.</p>
 */
final class PostgresReplicationCollector implements PostgresCollector {

    private static final String RECOVERY_SQL = "select pg_is_in_recovery() as in_recovery,"
            + " (to_regclass('pg_catalog.pg_stat_checkpointer') is not null) as has_checkpointer";

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
        PostgresRows<ServerShape> recovery = PostgresQuery.readOne(
                context,
                "Recovery state",
                RECOVERY_SQL,
                resultSet ->
                        new ServerShape(resultSet.getBoolean("in_recovery"), resultSet.getBoolean("has_checkpointer")));
        if (!recovery.available()) {
            return failed(recovery.reason());
        }
        ServerShape shape = recovery.empty()
                ? new ServerShape(false, false)
                : recovery.rows().get(0);
        boolean inRecovery = shape.inRecovery();

        List<PostgresReplicaDto> replicas = List.of();
        // Every sub-read's limitation is kept: on a standby the expected "lag is not measurable" note would
        // otherwise mask a genuine checkpoint or replication-slot failure read straight after it.
        List<String> limitations = new ArrayList<>();
        boolean truncated = false;
        if (!inRecovery) {
            PostgresRows<PostgresReplicaDto> rows = PostgresQuery.readList(
                    context,
                    "Replication statistics",
                    REPLICAS_SQL,
                    context.limits().maxReplicas(),
                    resultSet -> new PostgresReplicaDto(
                            resultSet.getString("application_name"),
                            clientAddress(resultSet.getString("client_addr"), context),
                            resultSet.getString("state"),
                            resultSet.getString("sync_state"),
                            PostgresQuery.longOrNull(resultSet, "sent_lag"),
                            PostgresQuery.longOrNull(resultSet, "flush_lag"),
                            PostgresQuery.longOrNull(resultSet, "replay_lag")));
            if (rows.available()) {
                replicas = rows.rows();
                truncated = rows.truncated();
            } else {
                limitations.add(rows.reason());
            }
        } else {
            limitations.add("This server is a standby, so replica lag is not measurable from here.");
        }

        PostgresRows<Checkpoints> checkpointRows = readCheckpoints(context, shape.hasCheckpointer());
        Checkpoints checkpoints = checkpointRows.available() && !checkpointRows.empty()
                ? checkpointRows.rows().get(0)
                : new Checkpoints(null, null, null);
        if (!checkpointRows.available()) {
            limitations.add(checkpointRows.reason());
        }

        PostgresRows<Slots> slots = PostgresQuery.readOne(
                context,
                "Replication slots",
                SLOTS_SQL,
                resultSet -> new Slots(
                        PostgresQuery.longOrNull(resultSet, "slots"),
                        PostgresQuery.longOrNull(resultSet, "inactive_slots")));
        Slots slotCounts = slots.available() && !slots.empty() ? slots.rows().get(0) : new Slots(null, null);
        if (!slots.available()) {
            limitations.add(slots.reason());
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
        if (truncated) {
            limitations.add("More replicas are connected than the read's replica bound allows.");
        }
        return limitations.isEmpty()
                ? available(rowCount, false)
                : partial(rowCount, String.join(" ", limitations), truncated);
    }

    /** PostgreSQL 17 moved the checkpoint counters from {@code pg_stat_bgwriter} to {@code pg_stat_checkpointer}. */
    static String checkpointSql(boolean hasCheckpointer) {
        if (hasCheckpointer) {
            return "select num_timed as checkpoints_timed, num_requested as checkpoints_requested,"
                    + " write_time as write_time from pg_stat_checkpointer";
        }
        return "select checkpoints_timed as checkpoints_timed, checkpoints_req as checkpoints_requested,"
                + " checkpoint_write_time as write_time from pg_stat_bgwriter";
    }

    private PostgresRows<Checkpoints> readCheckpoints(PostgresReadContext context, boolean hasCheckpointer) {
        return PostgresQuery.readOne(context, "Checkpoint statistics", checkpointSql(hasCheckpointer), resultSet -> {
            Double writeMillis = PostgresQuery.doubleOrNull(resultSet, "write_time");
            return new Checkpoints(
                    PostgresQuery.longOrNull(resultSet, "checkpoints_timed"),
                    PostgresQuery.longOrNull(resultSet, "checkpoints_requested"),
                    writeMillis == null ? null : writeMillis / 1000d);
        });
    }

    /** A replica's address, masked when the exposure policy allows metadata only. */
    private static String clientAddress(String address, PostgresReadContext context) {
        if (address == null) {
            return null;
        }
        ExposurePolicy exposure = context.exposure();
        ValueExposure valueExposure = exposure == null ? ValueExposure.MASKED : exposure.valueExposure();
        return valueExposure == ValueExposure.METADATA_ONLY ? SecretMasker.MASKED_VALUE : address;
    }

    /** Whether this server is a standby, and whether it carries the PostgreSQL 17 checkpointer view. */
    private record ServerShape(boolean inRecovery, boolean hasCheckpointer) {}

    private record Checkpoints(Long timed, Long requested, Double writeSeconds) {}

    private record Slots(Long slots, Long inactiveSlots) {}
}
