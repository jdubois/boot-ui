package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/** Dependency-free inactive bean used while durable persistence is disabled. */
public final class InactiveDurableJournalStore implements DurableJournalStore {

    @Override
    public void verifySchema() {}

    @Override
    public DurableJournalRun findRun(DurableJournalScope scope) {
        return null;
    }

    @Override
    public DurableJournalRunMetadata findRunMetadata(DurableJournalScope scope) {
        return null;
    }

    @Override
    public List<DurableJournalRun> listRuns(String namespace, String application) {
        return List.of();
    }

    @Override
    public List<DurableJournalRun> listRuns(String namespace, String application, int limit) {
        return List.of();
    }

    @Override
    public DurableJournalRun upsertRun(DurableJournalRun run) {
        return run;
    }

    @Override
    public int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events) {
        return 0;
    }

    @Override
    public int appendBatch(DurableJournalScope scope, List<JournalEntry> entries) {
        return 0;
    }

    @Override
    public DurableJournalPage query(DurableJournalQuery query) {
        return DurableJournalPage.EMPTY;
    }

    @Override
    public DurableJournalFeedPage queryFeed(DurableJournalFeedQuery query) {
        return DurableJournalFeedPage.EMPTY;
    }

    @Override
    public DurableJournalGlobalCursor globalCursor(DurableJournalScope scope, long journalSequence) {
        return null;
    }

    @Override
    public DurableJournalRun checkpoint(DurableJournalScope scope, long sequence) {
        return findRun(scope);
    }

    @Override
    public boolean upsertDetail(DurableJournalDetail detail) {
        return false;
    }

    @Override
    public List<DurableJournalDetail> listDetails(DurableJournalScope scope) {
        return List.of();
    }
}
