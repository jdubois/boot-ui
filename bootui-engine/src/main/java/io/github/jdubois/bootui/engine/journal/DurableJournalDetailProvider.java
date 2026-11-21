package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * Derives allow-listed auxiliary detail from a successfully appended journal batch.
 *
 * <p>Implementations receive the same masked, disk-bound {@link JournalEntry} values passed to the durable writer.
 * They must never read or retain mutable runtime objects, throwable messages, request bodies, paths, or principals.
 * Missing source data is represented by no detail, not by guessed or reconstructed values.</p>
 */
@FunctionalInterface
public interface DurableJournalDetailProvider {

    List<DurableJournalDetail> details(DurableJournalScope scope, List<JournalEntry> entries);
}
