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

    @Override
    public int estimatedBytes() {
        return 24
                + RuntimeEvent.stringBytes(groupId)
                + RuntimeEvent.stringBytes(exceptionClass)
                + RuntimeEvent.stringBytes(signature);
    }
}
