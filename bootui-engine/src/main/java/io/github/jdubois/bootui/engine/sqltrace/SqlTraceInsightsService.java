package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlRouteAttributionDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceWindowDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Assembles the slow-SQL ranking and per-route attribution served by the SQL Trace panel.
 *
 * <p>This is the one place where the bounded SQL Trace window, the normalized statement ranking and the
 * request attribution are combined, so all three adapters answer identically from identical evidence and
 * neither Spring nor Quarkus has to reproduce any of the policy.</p>
 *
 * <p>The report is a read over already-captured evidence. It runs no query, opens no connection, calls
 * nothing over the network, records nothing new and retains nothing beyond the SQL Trace buffer: every
 * figure describes the window in {@link SqlTraceWindowDto} and is diagnostic evidence for the current
 * session, not a lifetime metric.</p>
 */
public final class SqlTraceInsightsService {

    private final SqlTraceRecorder recorder;

    public SqlTraceInsightsService(SqlTraceRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Builds the report.
     *
     * @param requests captured inbound requests to attribute against; empty when the adapter has no HTTP
     *     exchange evidence, which is reported honestly rather than silently
     * @param supported the correlation tiers this runtime can offer
     * @param templates the application's declared route templates, so a request whose capture point could
     *     not report one is still grouped by a declared route rather than a masked path
     */
    public SqlTraceInsightsReport insights(
            List<SqlRequestEvidence> requests,
            Set<SqlRouteAttribution.Correlation> supported,
            RouteTemplateResolver templates) {
        return insights(requests, supported, templates, null);
    }

    /**
     * Builds the report, with route attribution reported as unavailable when
     * {@code attributionUnavailableReason} is non-null.
     *
     * <p>An adapter uses this when the runtime cannot supply request evidence at all — a WebFlux
     * application without the OpenTelemetry integration that carries request trace context, for example.
     * Saying so is the honest alternative to advertising correlation tiers over an empty candidate list,
     * which would render every retained statement as unattributed and look like a finding about the
     * application rather than a gap in BootUI's evidence.</p>
     */
    public SqlTraceInsightsReport insights(
            List<SqlRequestEvidence> requests,
            Set<SqlRouteAttribution.Correlation> supported,
            RouteTemplateResolver templates,
            String attributionUnavailableReason) {
        if (!recorder.isEnabled() || !recorder.hasWrappedDataSource()) {
            return SqlTraceInsightsReport.unavailable(
                    "SQL tracing is not active, so there are no retained statements to rank.");
        }

        // Rankings never need bound values: they group by literal-free normalized SQL, so parameters are
        // dropped here regardless of the exposure policy rather than relying on a caller to do it.
        List<SqlTraceEntryDto> entries = recorder.entries(false);
        SqlStatementRanking.Ranked ranked =
                SqlStatementRanking.rank(entries, SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD);
        SqlRouteAttributionDto attribution = attributionUnavailableReason != null
                ? SqlRouteAttributionDto.unavailable(attributionUnavailableReason)
                : SqlRouteAttribution.attribute(entries, requests, supported, templates, ranked.totalDurationMicros());

        return new SqlTraceInsightsReport(
                true,
                null,
                recorder.isRecording(),
                window(entries, ranked.totalDurationMicros()),
                ranked.statements(),
                SqlStatementRanking.TOP_PER_CRITERION,
                ranked.truncated(),
                ranked.distinct(),
                attribution,
                notes(entries, ranked));
    }

    private SqlTraceWindowDto window(List<SqlTraceEntryDto> entries, long totalDurationMicros) {
        Long oldest = entries.isEmpty() ? null : entries.get(0).timestamp();
        Long newest = entries.isEmpty() ? null : entries.get(entries.size() - 1).timestamp();
        for (SqlTraceEntryDto entry : entries) {
            oldest = Math.min(oldest, entry.timestamp());
            newest = Math.max(newest, entry.timestamp());
        }
        return new SqlTraceWindowDto(
                entries.size(),
                recorder.getMaxEntries(),
                recorder.evicted(),
                recorder.totalCaptured(),
                oldest,
                newest,
                SqlDurations.millis(totalDurationMicros));
    }

    private List<String> notes(List<SqlTraceEntryDto> entries, SqlStatementRanking.Ranked ranked) {
        List<String> notes = new ArrayList<>();
        notes.add("Rankings cover the " + entries.size() + " statement executions currently retained in the "
                + "SQL Trace buffer of " + recorder.getMaxEntries()
                + ". They are diagnostic evidence for this window, not lifetime metrics.");
        if (recorder.evicted() > 0) {
            notes.add("Older executions have been dropped from the buffer, so totals and percentiles "
                    + "under-report work that has already aged out.");
        }
        if (!recorder.isRecording()) {
            notes.add("Recording is paused, so the window will not grow until it is resumed.");
        }
        if (ranked.truncated()) {
            notes.add("Only the top " + SqlStatementRanking.TOP_PER_CRITERION
                    + " statements per ranking criterion are listed; " + ranked.distinct()
                    + " distinct normalized statements were observed.");
        }
        notes.add("Statements are grouped after normalization: literal values are replaced with "
                + "placeholders, so equivalent executions aggregate and no bound value is shown.");
        return notes;
    }
}
