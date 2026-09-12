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
 *
 * <p>The view is also located through the catalog rather than through {@code search_path}. An extension
 * installed into a dedicated schema — a common convention for monitoring extensions — is reported as
 * installed by {@code pg_extension} while an unqualified {@code from pg_stat_statements} fails with
 * "relation does not exist". Rendering the view's {@code oid} as {@code regclass} yields the name
 * PostgreSQL itself would use, schema-qualified and quoted only when {@code search_path} makes that
 * necessary.</p>
 */
final class PostgresStatementCollector implements PostgresCollector {

    private static final String EXTENSION_SQL = """
            select (select c.oid::regclass::text
                      from pg_class c
                      join pg_extension e on e.extname = 'pg_stat_statements' and e.extnamespace = c.relnamespace
                     where c.relname = 'pg_stat_statements') as relation,
                   (select count(*) from pg_attribute a
                      join pg_class c on c.oid = a.attrelid and c.relname = 'pg_stat_statements'
                      join pg_extension e on e.extname = 'pg_stat_statements' and e.extnamespace = c.relnamespace
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
                resultSet -> new Extension(resultSet.getString("relation"), resultSet.getInt("exec_naming") > 0));
        if (!extension.available()) {
            return failed(extension.reason());
        }
        if (extension.empty() || extension.rows().get(0).relation() == null) {
            return skipped("The pg_stat_statements extension is not installed on this server.", INSTALL_HINT);
        }
        Extension installed = extension.rows().get(0);

        PostgresRows<PostgresStatementDto> rows = PostgresQuery.readList(
                context,
                "Statement statistics",
                sql(installed.relation(), installed.execNaming()),
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
        if (data.statisticsRestricted()) {
            return partial(rows.rows().size(), RESTRICTED_LIMITATION, rows.truncated());
        }
        return available(rows.rows().size(), rows.truncated());
    }

    /**
     * The literal PostgreSQL substitutes for a statement the connected role may not read.
     *
     * <p>{@code pg_stat_statements} restricts the opposite way to {@code pg_stat_activity}: it keeps every
     * row, with real call counts and timings, and replaces only the text. Rendering those rows as if they
     * were statements would present this placeholder as the application's top query.</p>
     */
    static final String INSUFFICIENT_PRIVILEGE = "<insufficient privilege>";

    static final String RESTRICTED_LIMITATION =
            "pg_stat_statements shows the text of statements run by other roles as \"<insufficient "
                    + "privilege>\"; their timings are real but the statements cannot be identified. Grant the "
                    + "BootUI role membership of pg_monitor to read them.";

    private static Double hitRatio(Long hits, Long reads) {
        if (hits == null || reads == null) {
            return null;
        }
        long total = hits + reads;
        return total <= 0 ? null : hits.doubleValue() / total;
    }

    /**
     * The catalog-resolved relation name, accepted only when it looks like the identifier PostgreSQL itself
     * renders. The value comes from the inspected server's own {@code pg_class}, never from a user, but this
     * query is assembled as text, so an unexpected shape falls back to the bare name rather than being
     * interpolated.
     */
    private static final java.util.regex.Pattern RELATION_NAME = java.util.regex.Pattern.compile(
            "(?:[a-z_][a-z0-9_$]*|\"(?:[^\"]|\"\")+\")" + "(?:\\.(?:[a-z_][a-z0-9_$]*|\"(?:[^\"]|\"\")+\"))?");

    /** pg_stat_statements 1.8 renamed the timing columns; older extension versions only have {@code *_time}. */
    static String sql(String relation, boolean execNaming) {
        String view = relation != null && RELATION_NAME.matcher(relation).matches() ? relation : "pg_stat_statements";
        String total = execNaming ? "total_exec_time" : "total_time";
        String mean = execNaming ? "mean_exec_time" : "mean_time";
        String max = execNaming ? "max_exec_time" : "max_time";
        return "select s.queryid::text as query_id, s.query as query, s.calls as calls, s." + total
                + " as total_time, s." + mean + " as mean_time, s." + max + " as max_time, s.rows as rows_returned,"
                + " s.shared_blks_hit as shared_blks_hit, s.shared_blks_read as shared_blks_read"
                + " from " + view + " s"
                + " join pg_database d on d.oid = s.dbid and d.datname = current_database()"
                + " order by s." + total + " desc nulls last limit ?";
    }

    /**
     * The view's catalog-resolved name, {@code null} when the extension is not installed, and whether it is
     * new enough to use the {@code *_exec_time} column names.
     */
    private record Extension(String relation, boolean execNaming) {}
}
