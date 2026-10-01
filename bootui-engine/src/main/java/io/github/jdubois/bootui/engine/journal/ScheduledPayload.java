package io.github.jdubois.bootui.engine.journal;

/** A scheduled run's payload: the task it ran and, when it failed, the exception class. Never its message. */
public record ScheduledPayload(String task, String exceptionClass) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(task) + RuntimeEvent.stringBytes(exceptionClass);
    }
}
