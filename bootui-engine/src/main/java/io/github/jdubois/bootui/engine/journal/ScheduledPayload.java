package io.github.jdubois.bootui.engine.journal;

/** A scheduled run's payload: the task it ran and, when it failed, the exception class. Never its message. */
public record ScheduledPayload(String task, String exceptionClass) implements RuntimeEventPayload {

    /** This run with its task and exception class replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new ScheduledPayload(dictionary.shared(task), dictionary.shared(exceptionClass));
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 16
                + JournalDictionary.retained(dictionary, task)
                + JournalDictionary.retained(dictionary, exceptionClass);
    }
}
