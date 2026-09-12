package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresVacuumDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads autovacuum health from {@code pg_stat_user_tables}: dead-tuple ratio, the last (auto)vacuum and
 * (auto)analyze timestamps, and whether autovacuum is actually due.
 *
 * <p>"Due" is computed against the server's real {@code autovacuum_vacuum_threshold} and
 * {@code autovacuum_vacuum_scale_factor}, read by {@link PostgresSettingsCollector} in the same read, rather
 * than against the shipped defaults — a tuned server must not be judged by numbers it never used. Per-table
 * {@code reloptions} overrides are not read, so a table with its own autovacuum settings is judged by the
 * cluster values; that limitation is reported rather than hidden.</p>
 *
 * <p>Two deliberate approximations remain. The threshold is computed from {@code n_live_tup}, the
 * statistics collector's live-tuple estimate, whereas autovacuum itself uses {@code pg_class.reltuples};
 * the two agree except immediately after a bulk change. The comparison is strict, matching PostgreSQL's own
 * {@code n_dead_tup > threshold} test, so a table exactly at its threshold is not yet due.</p>
 */
final class PostgresVacuumCollector implements PostgresCollector {

    private static final String SQL = """
            select schemaname as schema_name, relname as table_name,
                   n_live_tup as live_tuples, n_dead_tup as dead_tuples,
                   last_vacuum, last_autovacuum, last_analyze, last_autoanalyze
            from pg_stat_user_tables
            order by n_dead_tup desc nulls last
            limit ?
            """;

    static final String RELOPTIONS_LIMITATION =
            "Autovacuum \"due\" is computed from the cluster-wide autovacuum settings against the live-tuple "
                    + "estimate in pg_stat_user_tables; per-table reloptions overrides are not read, and "
                    + "autovacuum itself uses pg_class.reltuples.";

    @Override
    public String id() {
        return PostgresSectionIds.VACUUM;
    }

    @Override
    public String title() {
        return "Autovacuum health";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        boolean autovacuumEnabled = !"off".equalsIgnoreCase(String.valueOf(data.setting("autovacuum")));
        double threshold = data.settingAsDouble("autovacuum_vacuum_threshold", 50);
        double scaleFactor = data.settingAsDouble("autovacuum_vacuum_scale_factor", 0.2);

        PostgresRows<PostgresVacuumDto> rows = PostgresQuery.readList(
                context, "Vacuum statistics", SQL, context.limits().maxVacuumTables(), resultSet -> {
                    Long live = PostgresQuery.longOrNull(resultSet, "live_tuples");
                    Long dead = PostgresQuery.longOrNull(resultSet, "dead_tuples");
                    Long trigger = vacuumThreshold(live, threshold, scaleFactor);
                    return new PostgresVacuumDto(
                            resultSet.getString("schema_name"),
                            resultSet.getString("table_name"),
                            live,
                            dead,
                            deadTupleRatio(live, dead),
                            trigger,
                            dead != null && trigger != null && dead > trigger,
                            autovacuumEnabled,
                            PostgresQuery.epochMillisOrNull(resultSet, "last_vacuum"),
                            PostgresQuery.epochMillisOrNull(resultSet, "last_autovacuum"),
                            PostgresQuery.epochMillisOrNull(resultSet, "last_analyze"),
                            PostgresQuery.epochMillisOrNull(resultSet, "last_autoanalyze"));
                });
        if (!rows.available()) {
            return failed(rows.reason());
        }
        List<PostgresVacuumDto> retained = new ArrayList<>(rows.rows());
        data.vacuum(retained);
        if (data.setting("autovacuum_vacuum_threshold") == null) {
            return partial(
                    retained.size(),
                    "The autovacuum settings could not be read, so \"due\" is computed from PostgreSQL's defaults.",
                    rows.truncated());
        }
        return available(retained.size(), rows.truncated());
    }

    /**
     * PostgreSQL compares an integer dead-tuple count against the fractional expression
     * {@code autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * reltuples}. Flooring that
     * expression keeps the integer comparison {@code dead > floor(t)} exactly equivalent to PostgreSQL's
     * {@code dead > t}, which rounding would not: a threshold of 999.6 rounds to 1000 and would wrongly
     * report 1,000 dead tuples as not yet due.
     */
    static Long vacuumThreshold(Long liveTuples, double threshold, double scaleFactor) {
        if (liveTuples == null) {
            return null;
        }
        return (long) Math.floor(threshold + scaleFactor * liveTuples);
    }

    static Double deadTupleRatio(Long liveTuples, Long deadTuples) {
        if (deadTuples == null) {
            return null;
        }
        long total = deadTuples + (liveTuples == null ? 0L : liveTuples);
        return total <= 0 ? null : deadTuples.doubleValue() / total;
    }
}
