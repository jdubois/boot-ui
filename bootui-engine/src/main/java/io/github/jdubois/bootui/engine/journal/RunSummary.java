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
 * @param sideEffects what the run did outside the JVM, as the BootUI agent's Side Effects saw it (M5-7b), or {@code
 *     null} when it ran without them
 */
public record RunSummary(Header header, AggregatesSnapshot aggregates, RunSideEffects sideEffects) {

    public RunSummary {
        Objects.requireNonNull(header, "header must not be null");
        Objects.requireNonNull(aggregates, "aggregates must not be null");
    }

    public RunSummary(Header header, AggregatesSnapshot aggregates) {
        this(header, aggregates, null);
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
        return of(run, aggregates, runStart, null, endedAtEpochMillis);
    }

    /**
     * The summary of {@code run}, which ended at {@code endedAtEpochMillis}, from its final aggregates, what it recorded
     * when it started, and its side effects, each possibly {@code null}.
     */
    public static RunSummary of(
            RunIdentity run,
            AggregatesSnapshot aggregates,
            RunStart runStart,
            RunSideEffects sideEffects,
            long endedAtEpochMillis) {
        return of(run, null, aggregates, runStart, sideEffects, endedAtEpochMillis);
    }

    /**
     * The summary of {@code run} of the application {@code application}, as {@link #applicationKey} names it, which
     * ended at {@code endedAtEpochMillis}, from its final aggregates, what it recorded when it started, and its side
     * effects, each possibly {@code null}.
     */
    public static RunSummary of(
            RunIdentity run,
            String application,
            AggregatesSnapshot aggregates,
            RunStart runStart,
            RunSideEffects sideEffects,
            long endedAtEpochMillis) {
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
                        runStart,
                        application),
                aggregates,
                sideEffects);
    }

    /**
     * The key of an application whose runs are compared with each other, as the BootUI agent's claim slot names it:
     * {@code mode:application}, such as {@code dev:orders}. Runs of another application, or of the same one in another
     * mode, which may share the JVM, are never compared with them. {@code null} when the application is unknown.
     *
     * @param mode {@code dev} or {@code test}
     * @param application the application's name
     */
    public static String applicationKey(String mode, String application) {
        if (application == null || application.isBlank()) {
            return null;
        }
        return (mode == null || mode.isBlank() ? "dev" : mode.strip()) + ":" + application.strip();
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
     * @param application the run's application, as {@link #applicationKey} names it, or {@code null} when unknown, as
     *     for a run an earlier BootUI kept
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
            RunStart runStart,
            String application) {

        public Header {
            Objects.requireNonNull(runId, "runId must not be null");
        }

        /** A header without its application. */
        public Header(
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
            this(
                    runId,
                    ordinal,
                    startedAtEpochMillis,
                    endedAtEpochMillis,
                    requests,
                    failedRequests,
                    events,
                    omittedEntries,
                    omittedEdges,
                    encodedBytes,
                    runStart,
                    null);
        }

        /**
         * Whether this run may be compared with a run of {@code application}: it is of the same application, or either
         * application is unknown, as for a run an earlier BootUI kept, which is compared as before.
         */
        public boolean comparableWith(String application) {
            return this.application == null || application == null || this.application.equals(application);
        }
    }
}
