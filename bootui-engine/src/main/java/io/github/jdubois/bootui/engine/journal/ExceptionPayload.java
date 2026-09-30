package io.github.jdubois.bootui.engine.journal;

/** An exception occurrence's payload: the group it belongs to and its exception class, never its message. */
public record ExceptionPayload(String groupId, String exceptionClass) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(groupId) + RuntimeEvent.stringBytes(exceptionClass);
    }
}
