package io.github.jdubois.bootui.engine.journal;

/**
 * An exception occurrence's payload: the group it belongs to, its exception class, and its cross-run signature, never
 * its message.
 *
 * @param groupId the Exceptions panel's group id, which includes line numbers
 * @param exceptionClass the exception's class name
 * @param signature a hash of the class and the declaring class and method of its top frames, without line numbers, so
 *     the same failure keeps its signature when an edit shifts its lines ({@code docs/PLAN-v2.md} §5.5), or
 *     {@code null} when unknown
 */
public record ExceptionPayload(String groupId, String exceptionClass, String signature) implements RuntimeEventPayload {

    /** An occurrence without a cross-run signature. */
    public ExceptionPayload(String groupId, String exceptionClass) {
        this(groupId, exceptionClass, null);
    }

    /** This occurrence with its group id, class, and signature replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new ExceptionPayload(
                dictionary.shared(groupId), dictionary.shared(exceptionClass), dictionary.shared(signature));
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 24
                + JournalDictionary.retained(dictionary, groupId)
                + JournalDictionary.retained(dictionary, exceptionClass)
                + JournalDictionary.retained(dictionary, signature);
    }
}
