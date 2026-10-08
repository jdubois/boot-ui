package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RuntimeAgentEvidenceDto;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearResult;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcePointDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceTotalsDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcesDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunSummaryDto;
import io.github.jdubois.bootui.engine.resources.ResourceTrack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime journal's status block, its resource track (§5.11), and its <b>Clear recording</b> action
 * ({@code docs/PLAN-v2.md} §5.2), shared by every adapter so they report the same shape and outcomes. Read-only policy blocks the action before it reaches this
 * service, through the {@code activity} panel's access rules.
 */
public final class RuntimeJournalService {

    static final String CONFIRMATION_REQUIRED = "Clear recording requires confirm=true because it drops every recorded"
            + " event and aggregate of this run, and the BootUI agent's evidence recorded with them.";

    static final String DISABLED = "The runtime journal is disabled (bootui.runtime-journal.enabled=false).";

    static final String RESOURCES_OFF =
            "The runtime journal does not record the resources source (bootui.runtime-journal.sources).";

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RunHistory history;
    private final AgentEvidence evidence;

    /**
     * A service reading this JVM's {@linkplain RunHistory#shared() shared} run history.
     *
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     */
    public RuntimeJournalService(RuntimeJournal journal, JournalAggregates aggregates) {
        this(journal, aggregates, (AgentEvidence) null);
    }

    /**
     * A service that also reports and clears the BootUI agent's evidence kept outside the journal (M5-11).
     *
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param evidence the agent evidence, which the adapter added to the journal as a listener so every clear clears
     *     it, or {@code null}
     */
    public RuntimeJournalService(RuntimeJournal journal, JournalAggregates aggregates, AgentEvidence evidence) {
        this(journal, aggregates, RunHistory.shared(), evidence);
    }

    RuntimeJournalService(RuntimeJournal journal, JournalAggregates aggregates, RunHistory history) {
        this(journal, aggregates, history, null);
    }

    RuntimeJournalService(
            RuntimeJournal journal, JournalAggregates aggregates, RunHistory history, AgentEvidence evidence) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.history = history;
        this.evidence = evidence;
    }

    /** The journal's status, or a disabled status when there is no journal. */
    public RuntimeJournalStatusDto status() {
        if (journal == null) {
            return new RuntimeJournalStatusDto(
                    false,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    0,
                    List.of(),
                    null,
                    evidenceStatus());
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
                history.unavailableReason(),
                evidenceStatus());
    }

    private RuntimeAgentEvidenceDto evidenceStatus() {
        try {
            return evidence == null ? null : evidence.status();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The run's resource track and CPU ledger ({@code docs/PLAN-v2.md} §5.11), or why the sampler does not run.
     */
    public RuntimeResourcesDto resources() {
        if (journal == null || !journal.settings().enabled()) {
            return unavailableResources(DISABLED);
        }
        if (aggregates == null || !journal.resourceSamplerRunning()) {
            return unavailableResources(RESOURCES_OFF);
        }
        ResourceTrack track = aggregates.resourceTrack();
        List<RuntimeResourcePointDto> points = new ArrayList<>();
        for (ResourceTrack.Point point : track.points()) {
            List<Long> families = new ArrayList<>();
            for (long part : point.familyCpuNanos()) {
                families.add(part);
            }
            points.add(new RuntimeResourcePointDto(
                    point.epochMillis(),
                    point.sequence(),
                    point.intervalNanos(),
                    point.processCpuNanos(),
                    point.requestCpuNanos(),
                    point.internalCpuNanos(),
                    families,
                    point.unreadThreads(),
                    point.heapUsedBytes(),
                    point.heapCommittedBytes(),
                    point.heapAfterGcBytes(),
                    point.allocatedBytes(),
                    point.liveThreads(),
                    point.daemonThreads()));
        }
        ResourceTrack.Totals totals = track.totals();
        return new RuntimeResourcesDto(
                true,
                null,
                track.families(),
                points,
                new RuntimeResourceTotalsDto(
                        totals.sweeps(),
                        totals.processCpuNanos(),
                        totals.requestCpuNanos(),
                        totals.internalCpuNanos(),
                        totals.familyCpuNanos()));
    }

    private static RuntimeResourcesDto unavailableResources(String reason) {
        return new RuntimeResourcesDto(
                false, reason, List.of(), List.of(), new RuntimeResourceTotalsDto(0, 0, 0, 0, Map.of()));
    }

    /** The kept summaries of the runs before this one, newest first. */
    private List<RuntimeRunSummaryDto> previousRuns(String currentRunId) {
        return history.headers(aggregates == null ? null : aggregates.application()).stream()
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
     * Drops every retained event and aggregate of the run, when confirmed, and with them the BootUI agent's evidence kept
     * outside the journal, which the journal clears through its {@link AgentEvidence} listener in the same step. Counts
     * since startup are kept, so drops and evictions stay visible.
     */
    public Response clear(RuntimeJournalClearRequest request) {
        if (journal == null || !journal.settings().enabled()) {
            return new Response(404, new RuntimeJournalClearResult("unavailable", DISABLED, 0));
        }
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return new Response(400, new RuntimeJournalClearResult("blocked", CONFIRMATION_REQUIRED, 0));
        }
        int cleared = journal.status().retainedEvents();
        long clearsBefore = evidence == null ? 0 : evidence.clears();
        journal.clear();
        if (aggregates != null && !journal.notifies(aggregates)) {
            // A listening aggregate is cleared in step with the journal; clearing it again would drop the batches
            // the journal records in between.
            aggregates.clear();
        }
        String agent = evidence != null && evidence.clears() != clearsBefore ? evidence.lastCleared() : null;
        return new Response(
                200,
                new RuntimeJournalClearResult(
                        "cleared",
                        "Cleared " + cleared + (cleared == 1 ? " recorded event" : " recorded events")
                                + " and the aggregates of this run"
                                + (agent == null ? "" : ", and the BootUI agent's evidence: " + agent)
                                + ".",
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
