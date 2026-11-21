package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * Minimal write-side SPI for durable journal capture.
 *
 * <p>An implementation acknowledges {@link #appendEvents} only after its crash-durability boundary. The JDBC
 * implementation acknowledges after transaction commit. A future append-only WAL or telemetry backend can provide a
 * different acknowledgement while remaining independent of the reader contract.</p>
 */
public interface DurableJournalWriter {

    DurableJournalRun upsertRun(DurableJournalRun run);

    /**
     * Durably appends one bounded batch of events and their optional projections and returns the number of newly
     * inserted rows. Full-key retries are idempotent; conflicting original-sequence bytes fail with
     * {@link DurableJournalConflictException}.
     */
    int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events);

    /**
     * Upserts one detail after its owning event append has been acknowledged. Implementations that do not support
     * auxiliary details may leave this operation unsupported; capture records the warning without retrying the
     * already-acknowledged event append.
     */
    default boolean upsertDetail(DurableJournalDetail detail) {
        throw new UnsupportedOperationException("Durable journal details are not supported by this writer");
    }

    /**
     * Deletes this run's events and auxiliary details through {@code sequence}, in one durability operation.
     *
     * <p>Implementations serialized with append acknowledgement make a clear reliable across an in-flight append:
     * an append completed before this operation is deleted, while a later sequence remains.
     */
    default DurableJournalDeletionCounts clearRunThrough(DurableJournalScope scope, long sequence) {
        throw new UnsupportedOperationException("Durable journal clearing is not supported by this writer");
    }

    /**
     * Deletes expired events and details and removes old runs made empty, within the namespace/application partition
     * of {@code scope}. The run portion identifies no retention boundary.
     */
    default DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis) {
        throw new UnsupportedOperationException("Durable journal retention is not supported by this writer");
    }

    /**
     * Prunes at most {@code maxRuns} run partitions in one call. The default is unbounded for implementations that do
     * not need an operational batch limit.
     */
    default DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis, int maxRuns) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
        return prune(scope, cutoffEpochMillis);
    }

    /**
     * Advances the consumer checkpoint monotonically. A clear may advance this past rows no longer retained; the
     * checkpoint is consumed sequence progress, not a claim that the corresponding rows still exist.
     */
    DurableJournalRun checkpoint(DurableJournalScope scope, long sequence);
}
