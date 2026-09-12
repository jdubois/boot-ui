package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresVacuumDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads autovacuum health from {@code pg_stat_user_tables}: dead-tuple ratio, the last (auto)vacuum and
 * (auto)analyze timestamps, and whether autovacuum is actually due.
 *
 * <p>"Due" is computed against the settings the server would actually use for each relation: the cluster's
 * {@code autovacuum_vacuum_threshold} and {@code autovacuum_vacuum_scale_factor} read by
 * {@link PostgresSettingsCollector} in the same read, each overridden by that table's own
 * {@code reloptions} where it sets one, and suppressed entirely where the table sets
 * {@code autovacuum_enabled = false}. A tuned server must not be judged by numbers it never used, and that
 * applies per table as much as per cluster.</p>
 *
 * <p>Two deliberate approximations remain, and both are reported rather than hidden. The threshold is
 * computed from {@code n_live_tup}, the statistics collector's live-tuple estimate, whereas autovacuum
 * itself uses {@code pg_class.reltuples}; the two agree except immediately after a bulk change. And only
 * the dead-tuple trigger is modelled: since PostgreSQL 13 an insert-only table can also be vacuumed by
 * {@code autovacuum_vacuum_insert_threshold}, so "not due" here means "not due for dead tuples". The
 * comparison is strict, matching PostgreSQL's own {@code n_dead_tup > threshold} test, so a table exactly
 * at its threshold is not yet due.</p>
 */
final class PostgresVacuumCollector implements PostgresCollector {

    static final String SQL = """
            select s.schemaname as schema_name, s.relname as table_name,
                   s.n_live_tup as live_tuples, s.n_dead_tup as dead_tuples,
                   (select option_value from pg_options_to_table(c.reloptions)
                      where option_name = 'autovacuum_vacuum_threshold') as rel_threshold,
                   (select option_value from pg_options_to_table(c.reloptions)
                      where option_name = 'autovacuum_vacuum_scale_factor') as rel_scale_factor,
                   (select option_value from pg_options_to_table(c.reloptions)
                      where option_name = 'autovacuum_enabled') as rel_autovacuum_enabled,
                   s.last_vacuum, s.last_autovacuum, s.last_analyze, s.last_autoanalyze
            from pg_stat_user_tables s join pg_class c on c.oid = s.relid
            order by s.n_dead_tup desc nulls last
            limit ?
            """;

    static final String ESTIMATE_LIMITATION =
            "Autovacuum \"due\" is computed against the live-tuple estimate in pg_stat_user_tables, whereas "
                    + "autovacuum itself uses pg_class.reltuples, and only the dead-tuple trigger is modelled: "
                    + "an insert-only table can also be vacuumed by autovacuum_vacuum_insert_threshold.";

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
        // PostgreSQL 18 added a cap on the scaled threshold. Older servers have no such setting, so its
        // absence means "no cap", which is exactly what a negative value means on a server that has it.
        double maxThreshold = data.settingAsDouble("autovacuum_vacuum_max_threshold", -1);

        PostgresRows<PostgresVacuumDto> rows = PostgresQuery.readList(
                context, "Vacuum statistics", SQL, context.limits().maxVacuumTables(), resultSet -> {
                    Long live = PostgresQuery.longOrNull(resultSet, "live_tuples");
                    Long dead = PostgresQuery.longOrNull(resultSet, "dead_tuples");
                    double tableThreshold = override(resultSet.getString("rel_threshold"), threshold);
                    double tableScaleFactor = override(resultSet.getString("rel_scale_factor"), scaleFactor);
                    boolean tableAutovacuum =
                            autovacuumEnabled && !isFalse(resultSet.getString("rel_autovacuum_enabled"));
                    Long trigger = vacuumThreshold(live, tableThreshold, tableScaleFactor, maxThreshold);
                    return new PostgresVacuumDto(
                            resultSet.getString("schema_name"),
                            resultSet.getString("table_name"),
                            live,
                            dead,
                            deadTupleRatio(live, dead),
                            trigger,
                            tableAutovacuum && dead != null && trigger != null && dead > trigger,
                            tableAutovacuum,
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
        return new PostgresSectionDto(
                id(), title(), "AVAILABLE", null, ESTIMATE_LIMITATION, retained.size(), rows.truncated());
    }

    /**
     * A per-table {@code reloptions} override, or the cluster value when the table sets none. An
     * unparseable override falls back to the cluster value rather than to a guess.
     */
    static double override(String relOption, double clusterValue) {
        if (relOption == null || relOption.isBlank()) {
            return clusterValue;
        }
        try {
            return Double.parseDouble(relOption.strip());
        } catch (NumberFormatException ex) {
            return clusterValue;
        }
    }

    /**
     * PostgreSQL accepts every spelling of a boolean storage parameter, so {@code autovacuum_enabled} can
     * come back as {@code false}, {@code off}, {@code no}, {@code 0} or an abbreviation of any of them.
     * Anything unrecognised is treated as "not disabled": autovacuum is on unless the table says otherwise.
     */
    static boolean isFalse(String relOption) {
        if (relOption == null || relOption.isBlank()) {
            return false;
        }
        String value = relOption.strip().toLowerCase(Locale.ROOT);
        if ("0".equals(value) || "f".equals(value) || "n".equals(value)) {
            return true;
        }
        // "o" alone is ambiguous between "on" and "off", and PostgreSQL rejects it; anything shorter than two
        // characters that is not one of the unambiguous spellings above is not treated as a disabling value.
        return value.length() >= 2 && ("false".startsWith(value) || "off".startsWith(value) || "no".startsWith(value));
    }

    /**
     * PostgreSQL compares an integer dead-tuple count against the fractional expression
     * {@code autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * reltuples}, capped since
     * PostgreSQL 18 by {@code autovacuum_vacuum_max_threshold} when that setting is not negative.
     * Flooring that expression keeps the integer comparison {@code dead > floor(t)} exactly equivalent to
     * PostgreSQL's {@code dead > t}, which rounding would not: a threshold of 999.6 rounds to 1000 and
     * would wrongly report 1,000 dead tuples as not yet due.
     */
    static Long vacuumThreshold(Long liveTuples, double threshold, double scaleFactor, double maxThreshold) {
        if (liveTuples == null) {
            return null;
        }
        double trigger = threshold + scaleFactor * liveTuples;
        if (maxThreshold >= 0) {
            trigger = Math.min(trigger, maxThreshold);
        }
        return (long) Math.floor(trigger);
    }

    static Double deadTupleRatio(Long liveTuples, Long deadTuples) {
        if (deadTuples == null) {
            return null;
        }
        long total = deadTuples + (liveTuples == null ? 0L : liveTuples);
        return total <= 0 ? null : deadTuples.doubleValue() / total;
    }
}
