package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresSessionDto;

/**
 * Snapshots the client backends {@code pg_stat_activity} reports at the instant of the read.
 *
 * <p>This is the panel's only live section: every other one reports counters accumulated since the last
 * statistics reset, while this one answers "what is the database doing right now" — which session is active,
 * which is idle inside an open transaction, which is waiting on a lock and on whom.</p>
 *
 * <p>{@code pg_stat_activity} degrades by nulling columns rather than by hiding rows, so a role without
 * {@code pg_monitor} still sees one row per backend but no state, wait event or statement for backends it
 * does not own. Those rows are kept — hiding them would under-report the server's real load — and the
 * section is reported as partially read so the blanks read as "not visible", never as "idle".</p>
 *
 * <p>Unlike {@code pg_stat_statements}, the statement text here is the verbatim text the client sent, so it
 * goes through the same redaction, masking and truncation as every other value BootUI exposes.</p>
 */
final class PostgresSessionCollector implements PostgresCollector {

    static final String RESTRICTED_LIMITATION =
            "pg_stat_activity hides the state and statement of backends this role does not own, so some "
                    + "sessions are listed without them. Grant the BootUI role membership of pg_monitor to read "
                    + "them.";

    private static final String SQL = """
            select a.pid as pid,
                   a.usename as user_name,
                   a.application_name as application_name,
                   host(a.client_addr) as client_address,
                   a.state as state,
                   a.wait_event_type as wait_event_type,
                   a.wait_event as wait_event,
                   (select string_agg(blocker::text, ', ') from unnest(pg_blocking_pids(a.pid)) as blocker)
                       as blocked_by,
                   extract(epoch from (now() - a.state_change)) as state_seconds,
                   extract(epoch from (now() - a.xact_start)) as transaction_seconds,
                   extract(epoch from (now() - a.query_start)) as query_seconds,
                   a.query as query
            from pg_stat_activity a
            where a.backend_type = 'client backend'
            order by (a.state = 'active') desc nulls last,
                     coalesce(a.xact_start, a.query_start) asc nulls last,
                     a.pid asc
            limit ?
            """;

    @Override
    public String id() {
        return PostgresSectionIds.SESSIONS;
    }

    @Override
    public String title() {
        return "Sessions";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<PostgresSessionDto> rows = PostgresQuery.readList(
                context,
                "Session activity",
                SQL,
                context.limits().maxSessions(),
                resultSet -> new PostgresSessionDto(
                        resultSet.getInt("pid"),
                        resultSet.getString("user_name"),
                        PostgresQueryText.truncate(
                                resultSet.getString("application_name"),
                                context.limits().maxQueryTextLength()),
                        resultSet.getString("client_address"),
                        resultSet.getString("state"),
                        resultSet.getString("wait_event_type"),
                        resultSet.getString("wait_event"),
                        resultSet.getString("blocked_by"),
                        PostgresQuery.doubleOrNull(resultSet, "state_seconds"),
                        PostgresQuery.doubleOrNull(resultSet, "transaction_seconds"),
                        PostgresQuery.doubleOrNull(resultSet, "query_seconds"),
                        PostgresQueryText.sanitize(
                                resultSet.getString("query"),
                                context.exposure(),
                                context.limits().maxQueryTextLength())));
        if (!rows.available()) {
            return failed(rows.reason());
        }
        data.sessions(rows.rows());
        boolean restricted = rows.rows().stream().anyMatch(session -> session.state() == null);
        if (restricted) {
            return partial(rows.rows().size(), RESTRICTED_LIMITATION, rows.truncated());
        }
        return available(rows.rows().size(), rows.truncated());
    }
}
