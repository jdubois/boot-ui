package io.github.jdubois.bootui.engine.journal;

import java.util.List;
import java.util.Objects;

/**
 * A bounded, point-in-time view consumed by journal-derived services.
 *
 * <p>The entries can combine a hot runtime journal with durable history. Completeness and loss information are read
 * through callbacks so persisted readers do not have to couple to the current {@link RuntimeJournal}. {@link
 * #omittedEvents()} reports evidence intentionally left out by the reader's event and byte bounds; it is a read
 * truncation, not journal eviction or durable-offload loss.</p>
 */
public final class JournalEvidenceSnapshot {

    /** Backend-neutral evidence-completeness operations needed by journal readers. */
    public interface Completeness {

        boolean records(JournalSource source);

        Long lossHorizonMillis();

        Long lostRequestEndMillis();

        boolean lostARequestOn(String thread);

        boolean evictedARequestOf(String traceId);

        Long lostMillis(JournalSource source);

        boolean sqlExecutionQualified();
    }

    private static final Completeness NONE = new Completeness() {
        @Override
        public boolean records(JournalSource source) {
            return true;
        }

        @Override
        public Long lossHorizonMillis() {
            return null;
        }

        @Override
        public Long lostRequestEndMillis() {
            return null;
        }

        @Override
        public boolean lostARequestOn(String thread) {
            return false;
        }

        @Override
        public boolean evictedARequestOf(String traceId) {
            return false;
        }

        @Override
        public Long lostMillis(JournalSource source) {
            return null;
        }

        @Override
        public boolean sqlExecutionQualified() {
            return false;
        }
    };

    private final List<JournalEntry> entries;
    private final JournalStatus status;
    private final long omittedEvents;
    private final long durableCaptureLoss;
    private final Completeness completeness;

    /** A caller-assembled bounded snapshot with no known loss. */
    public JournalEvidenceSnapshot(List<JournalEntry> entries, JournalStatus status, long omittedEvents) {
        this(entries, status, omittedEvents, 0, NONE);
    }

    public JournalEvidenceSnapshot(
            List<JournalEntry> entries, JournalStatus status, long omittedEvents, Completeness completeness) {
        this(entries, status, omittedEvents, 0, completeness);
    }

    public JournalEvidenceSnapshot(
            List<JournalEntry> entries,
            JournalStatus status,
            long omittedEvents,
            long durableCaptureLoss,
            Completeness completeness) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (omittedEvents < 0) {
            throw new IllegalArgumentException("omittedEvents must not be negative");
        }
        if (durableCaptureLoss < 0) {
            throw new IllegalArgumentException("durableCaptureLoss must not be negative");
        }
        this.omittedEvents = omittedEvents;
        this.durableCaptureLoss = durableCaptureLoss;
        this.completeness = Objects.requireNonNull(completeness, "completeness must not be null");
    }

    /** A point-in-time hot snapshot preserving the existing in-memory consumer contract. */
    public static JournalEvidenceSnapshot hot(RuntimeJournal journal) {
        Objects.requireNonNull(journal, "journal must not be null");
        RuntimeJournal.HotEvidence hot = journal.hotEvidence();
        return new JournalEvidenceSnapshot(hot.entries(), hot.status(), 0, new Completeness() {
            @Override
            public boolean records(JournalSource source) {
                return journal.records(source);
            }

            @Override
            public Long lossHorizonMillis() {
                return journal.lossHorizonMillis();
            }

            @Override
            public Long lostRequestEndMillis() {
                return journal.lostRequestEndMillis();
            }

            @Override
            public boolean lostARequestOn(String thread) {
                return journal.lostARequestOn(thread);
            }

            @Override
            public boolean evictedARequestOf(String traceId) {
                return journal.evictedARequestOf(traceId) || journal.lostArchiveTrace(traceId);
            }

            @Override
            public Long lostMillis(JournalSource source) {
                return journal.lostMillis(source);
            }

            @Override
            public boolean sqlExecutionQualified() {
                SqlCaptureScopes.Snapshot sql = journal.sqlCaptureScope();
                return !sql.executions().isEmpty() && sql.preparations().isEmpty() && !sql.incomplete();
            }
        });
    }

    /** Entries newest first. */
    public List<JournalEntry> entries() {
        return entries;
    }

    /** Status of the bounded evidence view rather than only of the current ring. */
    public JournalStatus status() {
        return status;
    }

    /** The number of unique available entries omitted to satisfy the reader's event or byte bound. */
    public long omittedEvents() {
        return omittedEvents;
    }

    /** Events shed or quarantined by the durable capture before reaching its store. */
    public long durableCaptureLoss() {
        return durableCaptureLoss;
    }

    public boolean complete() {
        return omittedEvents == 0 && durableCaptureLoss == 0;
    }

    public boolean partial() {
        return !complete();
    }

    /** This evidence with its decoded entries replaced while retaining status, loss, and completeness. */
    public JournalEvidenceSnapshot withEntries(List<JournalEntry> replacement) {
        return new JournalEvidenceSnapshot(replacement, status, omittedEvents, durableCaptureLoss, completeness);
    }

    public String eventId(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return status.runId() + "-" + entry.sequence();
    }

    public boolean records(JournalSource source) {
        return completeness.records(source);
    }

    public Long lossHorizonMillis() {
        return completeness.lossHorizonMillis();
    }

    public Long lostRequestEndMillis() {
        return completeness.lostRequestEndMillis();
    }

    public boolean lostARequestOn(String thread) {
        return completeness.lostARequestOn(thread);
    }

    public boolean evictedARequestOf(String traceId) {
        return completeness.evictedARequestOf(traceId);
    }

    public Long lostMillis(JournalSource source) {
        return completeness.lostMillis(source);
    }

    public boolean sqlExecutionQualified() {
        return completeness.sqlExecutionQualified();
    }
}
