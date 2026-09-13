package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresTableDto;

/**
 * Reads the largest relations from {@code pg_stat_user_tables}, with their live/dead tuple counts and their
 * sequential-versus-index scan shape — a missing-index radar rather than a verdict, since a small, fully
 * cached table is correctly sequentially scanned.
 */
final class PostgresTableCollector implements PostgresCollector {

    private static final String SQL = """
            select schemaname as schema_name, relname as table_name,
                   pg_total_relation_size(relid) as total_size,
                   pg_table_size(relid) as table_size,
                   pg_indexes_size(relid) as index_size,
                   n_live_tup as live_tuples, n_dead_tup as dead_tuples,
                   seq_scan as sequential_scans, seq_tup_read as sequential_tuples,
                   idx_scan as index_scans
            from pg_stat_user_tables
            order by total_size desc
            limit ?
            """;

    @Override
    public String id() {
        return PostgresSectionIds.TABLES;
    }

    @Override
    public String title() {
        return "Largest relations";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<PostgresTableDto> rows = PostgresQuery.readList(
                context, "Table statistics", SQL, context.limits().maxTables(), resultSet -> {
                    Long sequentialScans = PostgresQuery.longOrNull(resultSet, "sequential_scans");
                    Long indexScans = PostgresQuery.longOrNull(resultSet, "index_scans");
                    return new PostgresTableDto(
                            resultSet.getString("schema_name"),
                            resultSet.getString("table_name"),
                            PostgresQuery.longOrNull(resultSet, "total_size"),
                            PostgresQuery.longOrNull(resultSet, "table_size"),
                            PostgresQuery.longOrNull(resultSet, "index_size"),
                            PostgresQuery.longOrNull(resultSet, "live_tuples"),
                            PostgresQuery.longOrNull(resultSet, "dead_tuples"),
                            sequentialScans,
                            PostgresQuery.longOrNull(resultSet, "sequential_tuples"),
                            indexScans,
                            sequentialScanRatio(sequentialScans, indexScans));
                });
        if (!rows.available()) {
            return failed(rows.reason());
        }
        data.tables(rows.rows());
        return available(rows.rows().size(), rows.truncated());
    }

    static Double sequentialScanRatio(Long sequentialScans, Long indexScans) {
        if (sequentialScans == null) {
            return null;
        }
        long total = sequentialScans + (indexScans == null ? 0L : indexScans);
        return total <= 0 ? null : sequentialScans.doubleValue() / total;
    }
}
