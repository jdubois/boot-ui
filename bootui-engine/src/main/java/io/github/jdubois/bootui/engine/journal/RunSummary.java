package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import java.util.Objects;

/**
 * The summary of one application run's aggregates, kept after the run ends so a later run can be compared with it
 * ({@code docs/PLAN-v2.md} §5.2, §5.8). It holds route templates, statement fingerprints, exception-group ids, thread
 * families, the observed edges of the runtime model, counts, and histograms. The codec removes SQL literals before
 * keeping or writing a summary; an in-memory aggregate's grouping fingerprint is not itself a safe display form.
 *
 * @param header the run and its totals
 * @param aggregates the run's aggregates, possibly without their least-used entries, which {@code header} counts
 */
public record RunSummary(Header header, AggregatesSnapshot aggregates) {

    public RunSummary {
        Objects.requireNonNull(header, "header must not be null");
        Objects.requireNonNull(aggregates, "aggregates must not be null");
    }

    /** The summary of {@code run}, which ended at {@code endedAtEpochMillis}, from its final aggregates. */
    public static RunSummary of(RunIdentity run, AggregatesSnapshot aggregates, long endedAtEpochMillis) {
        return of(run, aggregates, null, endedAtEpochMillis);
    }

    /**
     * The summary of {@code run}, which ended at {@code endedAtEpochMillis}, from its final aggregates and what it
     * recorded when it started, or {@code null}.
     */
    public static RunSummary of(
            RunIdentity run, AggregatesSnapshot aggregates, RunStart runStart, long endedAtEpochMillis) {
        long events = aggregates.run().events().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        return new RunSummary(
                new Header(
                        run.id(),
                        run.ordinal(),
                        run.startedAtEpochMillis(),
                        endedAtEpochMillis,
                        aggregates.run().requests(),
                        aggregates.run().failedRequests(),
                        events,
                        0,
                        0,
                        0,
                        runStart),
                aggregates);
    }

    /**
     * A run and its totals, readable without decoding the rest of the summary.
     *
     * @param runId the run's id
     * @param ordinal the run's position among the runs of this BootUI instance
     * @param startedAtEpochMillis when the run started
     * @param endedAtEpochMillis when the run ended
     * @param requests the HTTP requests the run recorded
     * @param failedRequests those that answered with a {@code 5xx} status
     * @param events the events the run recorded, from every source
     * @param omittedEntries aggregate entries left out, least used first, to keep the summary within its byte bound
     * @param omittedEdges observed edges among them, least observed first, so a comparison can say an edge it reports as
     *     new may have been left out
     * @param encodedBytes the size of the encoded summary, or {@code 0} before it is encoded
     * @param runStart what the run recorded when it started ({@code docs/PLAN-v2.md} §5.18): its time to ready, its
     *     slowest startup steps, and its comparability facts, or {@code null} when it recorded none
     */
    public record Header(
            String runId,
            int ordinal,
            long startedAtEpochMillis,
            long endedAtEpochMillis,
            long requests,
            long failedRequests,
            long events,
            int omittedEntries,
            int omittedEdges,
            int encodedBytes,
            RunStart runStart) {

        public Header {
            Objects.requireNonNull(runId, "runId must not be null");
        }
    }
}
