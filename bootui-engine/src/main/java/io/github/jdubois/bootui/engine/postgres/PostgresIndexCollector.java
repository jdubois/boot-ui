package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresIndexDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;

/**
 * Reads index usage from {@code pg_stat_user_indexes}, least-used and largest first.
 *
 * <p>Scan counts are per node and reset with the statistics. A primary that never reads an index can still be
 * replicating it to a standby whose reporting queries depend on it, and an index created since the last
 * statistics reset has had no chance to be used yet. The panel therefore reports scan counts as evidence and
 * carries that caveat into every finding derived from them.</p>
 */
final class PostgresIndexCollector implements PostgresCollector {

    private static final String SQL = """
            select s.schemaname as schema_name, s.relname as table_name, s.indexrelname as index_name,
                   s.idx_scan as scans, s.idx_tup_read as tuples_read,
                   pg_relation_size(s.indexrelid) as size_bytes,
                   i.indisunique as is_unique, i.indisprimary as is_primary,
                   exists (select 1 from pg_constraint c where c.conindid = s.indexrelid) as constraint_backed
            from pg_stat_user_indexes s
            join pg_index i on i.indexrelid = s.indexrelid
            order by s.idx_scan asc nulls first, size_bytes desc
            limit ?
            """;

    @Override
    public String id() {
        return PostgresSectionIds.INDEXES;
    }

    @Override
    public String title() {
        return "Index usage";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<PostgresIndexDto> rows = PostgresQuery.readList(
                context,
                "Index usage statistics",
                SQL,
                context.limits().maxIndexes(),
                resultSet -> new PostgresIndexDto(
                        resultSet.getString("schema_name"),
                        resultSet.getString("table_name"),
                        resultSet.getString("index_name"),
                        PostgresQuery.longOrNull(resultSet, "scans"),
                        PostgresQuery.longOrNull(resultSet, "tuples_read"),
                        PostgresQuery.longOrNull(resultSet, "size_bytes"),
                        resultSet.getBoolean("is_unique"),
                        resultSet.getBoolean("is_primary"),
                        resultSet.getBoolean("constraint_backed")));
        if (!rows.available()) {
            return failed(rows.reason());
        }
        data.indexes(rows.rows());
        return available(rows.rows().size(), rows.truncated());
    }
}
