package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearResult;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunSummaryDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime journal's status block and its <b>Clear recording</b> action ({@code docs/PLAN-v2.md} §5.2), shared by
 * every adapter so they report the same shape and outcomes. Read-only policy blocks the action before it reaches this
 * service, through the {@code activity} panel's access rules.
 */
public final class RuntimeJournalService {

    static final String CONFIRMATION_REQUIRED =
            "Clear recording requires confirm=true because it drops every recorded event and aggregate of this run.";

    static final String DISABLED = "The runtime journal is disabled (bootui.runtime-journal.enabled=false).";

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RunHistory history;

    /**
     * A service reading this JVM's {@linkplain RunHistory#shared() shared} run history.
     *
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     */
    public RuntimeJournalService(RuntimeJournal journal, JournalAggregates aggregates) {
        this(journal, aggregates, RunHistory.shared());
    }

    RuntimeJournalService(RuntimeJournal journal, JournalAggregates aggregates, RunHistory history) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.history = history;
    }

    /** The journal's status, or a disabled status when there is no journal. */
    public RuntimeJournalStatusDto status() {
        if (journal == null) {
            return new RuntimeJournalStatusDto(
                    false, null, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, Map.of(), Map.of(), 0, List.of(), null);
        }
        JournalStatus status = journal.status();
        return new RuntimeJournalStatusDto(
                status.enabled(),
                status.runId(),
                status.retainedEvents(),
                status.retainedBytes() + status.dictionaryBytes(),
                status.maxEvents(),
                status.maxBytes(),
                status.reserved(),
                status.reservedCapacity(),
                status.evictedByCount() + status.evictedByBytes(),
                status.bindingBound(),
                status.oldestRetainedEpochMillis(),
                status.queueDepth(),
                status.queueCapacity(),
                byPropertyName(status.accepted()),
                byPropertyName(status.dropped()),
                status.droppedTotal(),
                previousRuns(status.runId()),
                history.unavailableReason());
    }

    /** The kept summaries of the runs before this one, newest first. */
    private List<RuntimeRunSummaryDto> previousRuns(String currentRunId) {
        return history.headers().stream()
                .filter(header -> !header.runId().equals(currentRunId))
                .map(header -> new RuntimeRunSummaryDto(
                        header.runId(),
                        header.ordinal(),
                        header.startedAtEpochMillis(),
                        header.endedAtEpochMillis(),
                        header.requests(),
                        header.failedRequests(),
                        header.events(),
                        header.encodedBytes(),
                        header.omittedEntries()))
                .toList();
    }

    /**
     * Drops every retained event and aggregate of the run, when confirmed. Counts since startup are kept, so drops and
     * evictions stay visible.
     */
    public Response clear(RuntimeJournalClearRequest request) {
        if (journal == null || !journal.settings().enabled()) {
            return new Response(404, new RuntimeJournalClearResult("unavailable", DISABLED, 0));
        }
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return new Response(400, new RuntimeJournalClearResult("blocked", CONFIRMATION_REQUIRED, 0));
        }
        int cleared = journal.status().retainedEvents();
        journal.clear();
        if (aggregates != null) {
            aggregates.clear();
        }
        return new Response(
                200,
                new RuntimeJournalClearResult(
                        "cleared",
                        "Cleared " + cleared + (cleared == 1 ? " recorded event" : " recorded events")
                                + " and the aggregates of this run.",
                        cleared));
    }

    private static Map<String, Long> byPropertyName(Map<JournalSource, Long> counts) {
        Map<String, Long> named = new LinkedHashMap<>();
        for (JournalSource source : JournalSource.values()) {
            Long count = counts.get(source);
            if (count != null) {
                named.put(source.propertyName(), count);
            }
        }
        return named;
    }

    /** An action outcome with its HTTP status. */
    public record Response(int status, RuntimeJournalClearResult body) {}
}
