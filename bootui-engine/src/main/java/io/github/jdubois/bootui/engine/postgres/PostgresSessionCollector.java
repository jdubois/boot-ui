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
 * <p>A role without {@code pg_read_all_stats} does not see a degraded version of this view: PostgreSQL
 * removes the rows of backends the role does not own entirely. Nothing in the result set reveals that, so
 * the section cannot infer its own completeness and instead asks the privilege probe taken before the read.
 * Without the privilege the list is reported as partially read, because a short list here means "this is
 * all I am allowed to see", never "this is all there is".</p>
 *
 * <p>Ages are measured against {@code clock_timestamp()} rather than {@code now()}. Every collector shares
 * one read-only transaction, so {@code now()} is frozen at the instant that transaction opened: it would
 * understate every age by however long the read has already taken, and report a negative age for any
 * session — including BootUI's own — whose statement began after the read started.</p>
 *
 * <p>Unlike {@code pg_stat_statements}, the statement text here is the verbatim text the client sent, so it
 * goes through the same redaction, masking and truncation as every other value BootUI exposes.</p>
 */
final class PostgresSessionCollector implements PostgresCollector {

    static final String RESTRICTED_LIMITATION =
            "pg_stat_activity hides the backends this role does not own, so this list covers only BootUI's "
                    + "own sessions and is not the server's real activity. Grant the BootUI role membership of "
                    + "pg_monitor to see every session.";

    static final String SQL = """
            select a.pid as pid,
                   a.usename as user_name,
                   a.application_name as application_name,
                   host(a.client_addr) as client_address,
                   a.state as state,
                   a.wait_event_type as wait_event_type,
                   a.wait_event as wait_event,
                   (select string_agg(blocker::text, ', ') from unnest(pg_blocking_pids(a.pid)) as blocker)
                       as blocked_by,
                   extract(epoch from (clock_timestamp() - a.state_change)) as state_seconds,
                   extract(epoch from (clock_timestamp() - a.xact_start)) as transaction_seconds,
                   extract(epoch from (clock_timestamp() - a.query_start)) as query_seconds,
                   a.query as query
            from pg_stat_activity a
            where a.backend_type = 'client backend'
              and a.datname = current_database()
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
                        PostgresQueryText.sanitize(
                                resultSet.getString("application_name"),
                                context.exposure(),
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
        if (data.statisticsRestricted()) {
            return partial(rows.rows().size(), RESTRICTED_LIMITATION, rows.truncated());
        }
        return available(rows.rows().size(), rows.truncated());
    }
}
