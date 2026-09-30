package io.github.jdubois.bootui.engine.journal;

/** A transaction's payload: the transactional method that began it and whether it rolled back. */
public record TransactionPayload(String method, boolean rolledBack) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(method);
    }
}
