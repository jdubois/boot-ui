package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder.CapturedEvent;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Verifies that the additive {@code RetryListener} observes Spring Retry rather than altering it: the
 * protected call still fails exactly as it would without BootUI, only metadata is recorded, and the
 * exception message never reaches the buffer.
 */
class BootUiRetryListenerTests {

    private static RetryTemplate template(FaultToleranceEventRecorder recorder, int maxAttempts) {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));
        template.setBackOffPolicy(new NoBackOffPolicy());
        template.registerListener(new BootUiRetryListener(recorder));
        return template;
    }

    @Test
    void capturesEachRetryAndTheFinalExhaustionWithoutChangingTheOutcome() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);
        RetryTemplate template = template(recorder, 3);

        assertThatThrownBy(() -> template.execute(context -> {
                    context.setAttribute(RetryContext.NAME, "payments");
                    throw new IOException("connect to db-42 failed");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("connect to db-42 failed");

        List<CapturedEvent> events = recorder.recent();
        assertThat(events)
                .extracting(CapturedEvent::outcome)
                .containsExactly(
                        FaultToleranceVocabulary.OUTCOME_RETRY_EXHAUSTED,
                        FaultToleranceVocabulary.OUTCOME_RETRY,
                        FaultToleranceVocabulary.OUTCOME_RETRY,
                        FaultToleranceVocabulary.OUTCOME_RETRY);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.policyName()).isEqualTo("payments");
            assertThat(event.provider()).isEqualTo(FaultToleranceVocabulary.PROVIDER_SPRING_RETRY);
            assertThat(event.policyType()).isEqualTo(FaultToleranceVocabulary.TYPE_RETRY);
            assertThat(event.failureCategory()).isEqualTo("IOException");
        });
    }

    @Test
    void capturesNothingOnASuccessfulCall() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);

        String result = template(recorder, 3).execute(context -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void attributesUnlabelledTemplateCallsHonestlyRatherThanGuessingAPolicy() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);

        assertThatThrownBy(() -> template(recorder, 1).execute(context -> {
                    throw new IllegalStateException("nope");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(recorder.recent())
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.policyName()).isEqualTo("(unnamed)"));
    }

    @Test
    void recordsNothingWhenCaptureIsDisabled() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(false, 50);

        assertThatThrownBy(() -> template(recorder, 2).execute(context -> {
                    throw new IllegalStateException("nope");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void rendersTheGeneratedInterceptorLabelWithTheSameNameAsThePolicyInventory() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);
        RetryTemplate template = template(recorder, 2);

        assertThatThrownBy(() -> template.execute(context -> {
                    context.setAttribute(RetryContext.NAME, "io.github.jdubois.sample.FlakyInventoryClient.reserve");
                    throw new IOException("connect to inventory failed");
                }))
                .isInstanceOf(IOException.class);

        // The inventory names the same policy "FlakyInventoryClient#reserve", so an event that kept the
        // fully qualified interceptor label would filter the panel down to no matching row at all.
        assertThat(recorder.recent())
                .extracting(CapturedEvent::policyName)
                .containsOnly("FlakyInventoryClient#reserve");
    }

    @Test
    void keepsAnExplicitLabelExactlyAsTheApplicationWroteIt() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 50);
        RetryTemplate template = template(recorder, 1);

        assertThatThrownBy(() -> template.execute(context -> {
                    context.setAttribute(RetryContext.NAME, "inventory.reserve");
                    throw new IOException("boom");
                }))
                .isInstanceOf(IOException.class);

        assertThat(recorder.recent()).extracting(CapturedEvent::policyName).containsOnly("inventory.reserve");
    }
}
