package io.github.jdubois.bootui.engine.journal;

/**
 * One fault-tolerance outcome ({@code docs/PLAN-v2.md} §5.2): the policy, its type, what it protected, and how it
 * ended, never the failure's message.
 *
 * @param policy the policy's name, such as {@code paymentGateway}
 * @param policyType the policy's type, such as {@code retry} or {@code circuit-breaker}
 * @param target what the policy protected, such as a method, or {@code null}
 * @param outcome the outcome, such as {@code RETRY}, {@code SUCCESS}, or {@code STATE_TRANSITION}
 * @param attempt the attempt it ended, or {@code null}
 * @param state a circuit breaker's new state, or {@code null}
 * @param failureCategory the class of failure, such as an exception class, or {@code null}
 * @param failure whether the outcome is a failure
 * @param protective whether the policy stepped in, such as a retry, a fallback, or an open breaker
 */
public record FaultTolerancePayload(
        String policy,
        String policyType,
        String target,
        String outcome,
        Integer attempt,
        String state,
        String failureCategory,
        boolean failure,
        boolean protective)
        implements RuntimeEventPayload {

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new FaultTolerancePayload(
                dictionary.shared(policy),
                dictionary.shared(policyType),
                dictionary.shared(target),
                dictionary.shared(outcome),
                attempt,
                dictionary.shared(state),
                dictionary.shared(failureCategory),
                failure,
                protective);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 40
                + JournalDictionary.retained(dictionary, policy)
                + JournalDictionary.retained(dictionary, policyType)
                + JournalDictionary.retained(dictionary, target)
                + JournalDictionary.retained(dictionary, outcome)
                + JournalDictionary.retained(dictionary, state)
                + JournalDictionary.retained(dictionary, failureCategory);
    }
}
