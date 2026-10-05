package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

/**
 * Captures Spring Retry outcomes as bounded metadata.
 *
 * <p>Spring Retry collects every {@code RetryListener} bean and invokes them all, so registering this
 * listener observes retries without replacing the application's own listeners or altering interceptor
 * behaviour. {@code open} keeps its default {@code true} result, which is what makes this observation and
 * not a veto.</p>
 *
 * <p>Only the attempt number and the failing exception's type are recorded — never the callback's arguments,
 * its result, or the exception message.</p>
 */
public class BootUiRetryListener implements RetryListener {

    private final FaultToleranceEventRecorder recorder;

    public BootUiRetryListener(FaultToleranceEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        record(context, FaultToleranceVocabulary.OUTCOME_RETRY, context.getRetryCount(), throwable);
    }

    @Override
    public <T, E extends Throwable> void close(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable != null) {
            record(context, FaultToleranceVocabulary.OUTCOME_RETRY_EXHAUSTED, context.getRetryCount(), throwable);
        }
    }

    private void record(RetryContext context, String outcome, int attempt, Throwable throwable) {
        if (!recorder.isEnabled()) {
            // The listener bean is published on class presence alone, because Spring Retry collects
            // RetryListener beans when it builds its interceptors and a bean condition cannot express the
            // full panel-enablement policy (property toggles plus include/exclude lists). Disabling the
            // panel or bootui.fault-tolerance.enabled therefore makes the listener inert here, before any
            // context attribute is read: BootUI observes nothing rather than merely hiding it.
            return;
        }
        recorder.record(
                policyName(context),
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_SPRING_RETRY,
                null,
                outcome,
                attempt,
                null,
                FaultToleranceVocabulary.failureCategory(throwable));
    }

    /**
     * Spring Retry stores the interceptor's label under {@link RetryContext#NAME}. When a caller uses the
     * template directly there is no label, and BootUI says so rather than attributing the event to a policy
     * it cannot identify.
     */
    private static String policyName(RetryContext context) {
        Object name = context.getAttribute(RetryContext.NAME);
        String text = name == null ? null : String.valueOf(name);
        return text == null || text.isBlank() ? "(unnamed)" : normalize(text.trim());
    }

    /**
     * Spring Retry labels an annotated method as {@code fully.qualified.Type.method}, while the policy
     * inventory names that same policy {@code Type#method}. Rewriting the generated label keeps an event and
     * its policy on one name, which is what makes filtering and the Live Activity deep link land on the
     * matching row. A label the user wrote themselves does not look like a qualified method reference and is
     * left exactly as written.
     */
    private static String normalize(String label) {
        int methodSeparator = label.lastIndexOf('.');
        if (methodSeparator <= 0 || methodSeparator == label.length() - 1) {
            return label;
        }
        String method = label.substring(methodSeparator + 1);
        String type = label.substring(0, methodSeparator);
        int typeSeparator = type.lastIndexOf('.');
        if (typeSeparator < 0 || typeSeparator == type.length() - 1) {
            return label;
        }
        String simpleType = type.substring(typeSeparator + 1);
        if (!isIdentifier(method) || !isIdentifier(simpleType) || !Character.isUpperCase(simpleType.charAt(0))) {
            return label;
        }
        return simpleType + "#" + method;
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
