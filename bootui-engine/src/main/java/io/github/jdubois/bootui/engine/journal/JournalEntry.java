package io.github.jdubois.bootui.engine.journal;

/**
 * A {@link RuntimeEvent} the journal accepted, with the sequence number the dispatcher gave it, unique within its run,
 * and the bytes it was estimated to retain.
 *
 * @param sequence the event's position in its run, starting at 1, in the order the journal accepted events
 * @param event the event
 * @param estimatedBytes the bytes the event retains, estimated once
 */
public record JournalEntry(long sequence, RuntimeEvent event, int estimatedBytes) {}
