package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Slow-SQL ranking and request attribution for the SQL Trace panel.
 *
 * <p>Served from {@code GET /bootui/api/sql-trace/insights}, alongside — not instead of — the
 * chronological {@link SqlTraceReport}. It is a separate read so the frequently polled trace response
 * stays cheap and so ranking never depends on request evidence the trace itself does not need.</p>
 *
 * <p>Everything here is derived from evidence BootUI has already captured: the bounded SQL Trace buffer
 * and the bounded HTTP exchange buffer. No database call, JDBC interception, extra statement recorder or
 * request capture is added, and nothing is retained beyond the existing SQL Trace window described by
 * {@link #window()}.</p>
 *
 * @param available whether SQL tracing is active and rankings could be computed
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param capturing whether new executions are currently being recorded
 * @param window the bounded retained window every figure below is computed over
 * @param statements ranked normalized statements, carrying every rankable metric on each row
 * @param topPerCriterion how many statements each ranking criterion contributes; {@link #statements()} is
 *     the union across criteria, so re-sorting it by any criterion and taking this many rows yields that
 *     criterion's exact top list
 * @param statementsTruncated whether further normalized statements exist beyond {@link #statements()}
 * @param distinctStatements distinct normalized statements observed in the window
 * @param attribution per-route database attribution, with explicit unattributed and ambiguous buckets
 * @param notes plain-language explanations of scope, bounds and limitations
 */
public record SqlTraceInsightsReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        SqlTraceWindowDto window,
        List<SqlStatementRankingDto> statements,
        int topPerCriterion,
        boolean statementsTruncated,
        int distinctStatements,
        SqlRouteAttributionDto attribution,
        List<String> notes) {

    public SqlTraceInsightsReport {
        window = window == null ? SqlTraceWindowDto.empty() : window;
        statements = DtoCollections.immutableCopy(statements);
        attribution = attribution == null ? SqlRouteAttributionDto.unavailable(null) : attribution;
        notes = DtoCollections.immutableCopy(notes);
    }

    public static SqlTraceInsightsReport unavailable(String reason) {
        return new SqlTraceInsightsReport(
                false,
                reason,
                false,
                SqlTraceWindowDto.empty(),
                List.of(),
                0,
                false,
                0,
                SqlRouteAttributionDto.unavailable(reason),
                List.of());
    }
}
