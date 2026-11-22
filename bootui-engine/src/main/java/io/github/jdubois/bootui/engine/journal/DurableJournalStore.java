package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * Backend-neutral durable journal boundary used by capture, historical Live Activity, and Runtime Insights reads.
 *
 * <p>An implementation must not acknowledge {@link #appendBatch} until the supplied rows have crossed that backend's
 * crash-durability boundary. JDBC implementations therefore acknowledge after commit; a future append-only WAL or
 * telemetry backend must document and implement its own fsync or delivery acknowledgement semantics. A retry with the
 * same scope and original journal sequence must be idempotent.</p>
 */
public interface DurableJournalStore extends DurableJournalWriter {

    int MAX_PROJECTION_LENGTH = 128;
    int MAX_TEXT_PROJECTION_LENGTH = 2_000;
    int MAX_DETAILS_PER_QUERY = 100_000;

    /** Verifies that the backend is readable, creating its schema only where configured to do so. */
    void verifySchema();

    DurableJournalRun findRun(DurableJournalScope scope);

    DurableJournalRunMetadata findRunMetadata(DurableJournalScope scope);

    /** Reads metadata and any confirmed Clear boundary together, or {@code null} if unsupported or absent. */
    default DurableJournalRunMetadataCodec.ArchivedMetadata findArchivedMetadata(DurableJournalScope scope) {
        return null;
    }

    List<DurableJournalRun> listRuns(String namespace, String application);

    List<DurableJournalRun> listRuns(String namespace, String application, int limit);

    /** Appends one event that may carry its already-derived Live Activity projection. */
    default boolean append(DurableJournalScope scope, DurableJournalEvent event) {
        return appendEvents(scope, List.of(event)) == 1;
    }

    /**
     * JournalEntry-only compatibility overload. Writers should use {@link #appendEvents(DurableJournalScope, List)} so
     * reader-facing projections derived by {@link JournalActivityFeed} are not lost.
     */
    int appendBatch(DurableJournalScope scope, List<JournalEntry> entries);

    @Override
    default DurableJournalDeletionCounts clearRunThrough(DurableJournalScope scope, long sequence) {
        return DurableJournalWriter.super.clearRunThrough(scope, sequence);
    }

    @Override
    default DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis) {
        return DurableJournalWriter.super.prune(scope, cutoffEpochMillis);
    }

    @Override
    default DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis, int maxRuns) {
        return DurableJournalWriter.super.prune(scope, cutoffEpochMillis, maxRuns);
    }

    /** Decodes retained typed events for profile/insight reconstruction. */
    DurableJournalPage query(DurableJournalQuery query);

    /** Reads projected Live Activity rows newest-first without decoding event payloads. */
    DurableJournalFeedPage queryFeed(DurableJournalFeedQuery query);

    DurableJournalGlobalCursor globalCursor(DurableJournalScope scope, long journalSequence);

    boolean upsertDetail(DurableJournalDetail detail);

    List<DurableJournalDetail> listDetails(DurableJournalScope scope);

    /**
     * Lists at most {@link #MAX_DETAILS_PER_QUERY} details for events at or after
     * {@code firstJournalSequence}, in sequence and kind order.
     */
    default List<DurableJournalDetail> listDetails(DurableJournalScope scope, long firstJournalSequence, int limit) {
        if (firstJournalSequence < 0) {
            throw new IllegalArgumentException("firstJournalSequence must not be negative");
        }
        int bounded = limit <= 0 ? DurableJournalQuery.DEFAULT_PAGE_SIZE : Math.min(limit, MAX_DETAILS_PER_QUERY);
        return listDetails(scope).stream()
                .filter(detail -> detail.journalSequence() >= firstJournalSequence)
                .limit(bounded)
                .toList();
    }
}
