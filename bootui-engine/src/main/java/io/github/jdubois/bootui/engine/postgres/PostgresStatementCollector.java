package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresStatementDto;

/**
 * Ranks normalized statements from {@code pg_stat_statements} by total execution time.
 *
 * <p>Two things are installation-gated, and both degrade the section rather than failing the read: the
 * extension may simply not be installed (reported with the exact {@code CREATE EXTENSION} statement that
 * would fix it), and {@code pg_stat_statements} 1.8 renamed {@code total_time}/{@code mean_time} to
 * {@code total_exec_time}/{@code mean_exec_time}. That rename follows the <em>extension</em> version, not
 * the server version: a PostgreSQL 13+ server that was upgraded without {@code ALTER EXTENSION ... UPDATE}
 * still exposes the old names. The column names are therefore read from the catalog rather than inferred
 * from the server version.</p>
 */
final class PostgresStatementCollector implements PostgresCollector {

    private static final String EXTENSION_SQL = """
            select (select count(*) from pg_extension where extname = 'pg_stat_statements')::int as installed,
                   (select count(*) from pg_attribute a
                      join pg_class c on c.oid = a.attrelid and c.relname = 'pg_stat_statements'
                     where a.attname = 'total_exec_time' and not a.attisdropped)::int as exec_naming
            """;

    static final String INSTALL_HINT = "Install the extension (shared_preload_libraries = 'pg_stat_statements', then "
            + "CREATE EXTENSION pg_stat_statements;) to rank statements by execution time.";

    @Override
    public String id() {
        return PostgresSectionIds.STATEMENTS;
    }

    @Override
    public String title() {
        return "Statement ranking";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<Extension> extension = PostgresQuery.readOne(
                context,
                "pg_stat_statements availability",
                EXTENSION_SQL,
                resultSet -> new Extension(resultSet.getInt("installed") > 0, resultSet.getInt("exec_naming") > 0));
        if (!extension.available()) {
            return failed(extension.reason());
        }
        if (extension.empty() || !extension.rows().get(0).installed()) {
            return skipped("The pg_stat_statements extension is not installed on this server.", INSTALL_HINT);
        }
        boolean execNaming = extension.rows().get(0).execNaming();

        PostgresRows<PostgresStatementDto> rows = PostgresQuery.readList(
                context,
                "Statement statistics",
                sql(execNaming),
                context.limits().maxStatements(),
                resultSet -> {
                    Long hits = PostgresQuery.longOrNull(resultSet, "shared_blks_hit");
                    Long reads = PostgresQuery.longOrNull(resultSet, "shared_blks_read");
                    return new PostgresStatementDto(
                            resultSet.getString("query_id"),
                            PostgresQueryText.sanitize(
                                    resultSet.getString("query"),
                                    context.exposure(),
                                    context.limits().maxQueryTextLength()),
                            PostgresQuery.longOrNull(resultSet, "calls"),
                            PostgresQuery.doubleOrNull(resultSet, "total_time"),
                            PostgresQuery.doubleOrNull(resultSet, "mean_time"),
                            PostgresQuery.doubleOrNull(resultSet, "max_time"),
                            PostgresQuery.longOrNull(resultSet, "rows_returned"),
                            hitRatio(hits, reads));
                });
        if (!rows.available()) {
            return failed(rows.reason());
        }
        data.statements(rows.rows());
        return available(rows.rows().size(), rows.truncated());
    }

    private static Double hitRatio(Long hits, Long reads) {
        if (hits == null || reads == null) {
            return null;
        }
        long total = hits + reads;
        return total <= 0 ? null : hits.doubleValue() / total;
    }

    /** pg_stat_statements 1.8 renamed the timing columns; older extension versions only have {@code *_time}. */
    static String sql(boolean execNaming) {
        String total = execNaming ? "total_exec_time" : "total_time";
        String mean = execNaming ? "mean_exec_time" : "mean_time";
        String max = execNaming ? "max_exec_time" : "max_time";
        return "select s.queryid::text as query_id, s.query as query, s.calls as calls, s." + total
                + " as total_time, s." + mean + " as mean_time, s." + max + " as max_time, s.rows as rows_returned,"
                + " s.shared_blks_hit as shared_blks_hit, s.shared_blks_read as shared_blks_read"
                + " from pg_stat_statements s"
                + " join pg_database d on d.oid = s.dbid and d.datname = current_database()"
                + " order by s." + total + " desc nulls last limit ?";
    }

    /** Whether the extension is installed, and whether it is new enough to use the {@code *_exec_time} names. */
    private record Extension(boolean installed, boolean execNaming) {}
}
