package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import java.util.Objects;

/**
 * The summary of one application run's aggregates, kept after the run ends so a later run can be compared with it
 * ({@code docs/PLAN-v2.md} §5.2, §5.8). It holds route templates, statement fingerprints, exception-group ids, thread
 * families, counts, and histograms, all of which the aggregates already hold without principals, values, or SQL
 * literals.
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
                        0),
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
     * @param encodedBytes the size of the encoded summary, or {@code 0} before it is encoded
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
            int encodedBytes) {

        public Header {
            Objects.requireNonNull(runId, "runId must not be null");
        }

        Header withEncoding(int omitted, int bytes) {
            return new Header(
                    runId,
                    ordinal,
                    startedAtEpochMillis,
                    endedAtEpochMillis,
                    requests,
                    failedRequests,
                    events,
                    omitted,
                    bytes);
        }
    }
}
