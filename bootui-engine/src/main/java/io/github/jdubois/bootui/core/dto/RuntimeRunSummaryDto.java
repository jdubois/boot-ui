package io.github.jdubois.bootui.core.dto;

/**
 * A previous application run whose summary BootUI keeps, for the runtime journal's status block
 * ({@code docs/PLAN-v2.md} §5.2, §5.8).
 *
 * @param runId the run's id
 * @param ordinal the run's position among the runs of this BootUI instance
 * @param startedAt when the run started, in epoch milliseconds
 * @param endedAt when the run ended, in epoch milliseconds
 * @param requests the HTTP requests the run recorded
 * @param failedRequests those that answered with a {@code 5xx} status
 * @param events the events the run recorded, from every source
 * @param summaryBytes the memory the summary uses
 * @param omittedEntries aggregate entries left out, least used first, to keep the summary within its bound
 */
public record RuntimeRunSummaryDto(
        String runId,
        int ordinal,
        long startedAt,
        long endedAt,
        long requests,
        long failedRequests,
        long events,
        int summaryBytes,
        int omittedEntries) {}
