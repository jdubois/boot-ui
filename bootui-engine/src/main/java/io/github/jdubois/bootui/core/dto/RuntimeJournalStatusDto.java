package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * The runtime journal's state, for Live Activity's status block ({@code docs/PLAN-v2.md} §5.2).
 *
 * @param enabled whether the journal records anything
 * @param runId the application run the journal records
 * @param retainedEvents the events retained as evidence
 * @param retainedBytes the estimated bytes those events and the run's dictionary retain
 * @param maxEvents the count bound
 * @param maxBytes the byte bound
 * @param reservedEvents failed and slow events held in their reserved share
 * @param reservedCapacity the reserved share of {@code maxEvents}
 * @param evictedEvents events evicted since the run started, by either bound
 * @param bindingBound the bound that forced the latest eviction, {@code COUNT} or {@code BYTES}, or {@code null}
 * @param oldestRetainedAt when the oldest retained event happened, in epoch milliseconds, or {@code null}
 * @param queueDepth events waiting to be recorded
 * @param queueCapacity the queue's capacity
 * @param recorded events recorded since the run started, per source, such as {@code sql} or {@code rest-client}
 * @param dropped events dropped because the queue was full, per source
 * @param droppedEvents events dropped from every source
 * @param previousRuns the previous runs whose summaries are kept, newest first
 * @param previousRunsUnavailable why previous runs cannot be kept, or {@code null} when they can
 * @param agentEvidence what the BootUI agent's evidence kept outside the journal retains, or {@code null} when no
 *     store of it is registered
 */
public record RuntimeJournalStatusDto(
        boolean enabled,
        String runId,
        int retainedEvents,
        long retainedBytes,
        int maxEvents,
        long maxBytes,
        int reservedEvents,
        int reservedCapacity,
        long evictedEvents,
        String bindingBound,
        Long oldestRetainedAt,
        int queueDepth,
        int queueCapacity,
        Map<String, Long> recorded,
        Map<String, Long> dropped,
        long droppedEvents,
        List<RuntimeRunSummaryDto> previousRuns,
        String previousRunsUnavailable,
        RuntimeAgentEvidenceDto agentEvidence) {

    public RuntimeJournalStatusDto {
        recorded = DtoCollections.immutableCopy(recorded);
        dropped = DtoCollections.immutableCopy(dropped);
        previousRuns = DtoCollections.immutableCopy(previousRuns);
    }
}
