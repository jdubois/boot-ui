package io.github.jdubois.bootui.core.dto;

/**
 * Backend-neutral origin metadata for one entry read from a durable Live Activity feed.
 *
 * @param entryId identifier of the matching {@link ActivityEntryDto}
 * @param namespace opaque durable namespace
 * @param application application key within the namespace
 * @param run durable application-run identifier
 * @param instanceId application instance that produced the run, when recorded
 * @param source original journal source
 * @param journalSequence original sequence within the run
 * @param globalSequence durable store ordering token
 */
public record ActivityEntryOriginDto(
        String entryId,
        String namespace,
        String application,
        String run,
        String instanceId,
        String source,
        long journalSequence,
        long globalSequence) {}
