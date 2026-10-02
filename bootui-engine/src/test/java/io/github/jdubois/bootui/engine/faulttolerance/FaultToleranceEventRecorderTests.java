package io.github.jdubois.bootui.engine.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultToleranceEventDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder.CapturedEvent;
import io.github.jdubois.bootui.engine.journal.FaultTolerancePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FaultToleranceEventRecorderTests {

    @Test
    void capturesEventsNewestFirst() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);

        recorder.record("first", "RETRY", "resilience4j", "Target#a", "RETRY", 1, 12L, "IOException");
        recorder.record("second", "RETRY", "resilience4j", "Target#b", "RETRY_EXHAUSTED", 3, 40L, "IOException");

        List<CapturedEvent> events = recorder.recent();
        assertThat(events).extracting(CapturedEvent::policyName).containsExactly("second", "first");
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }

    @Test
    void publishesEachOutcomeToTheRuntimeJournalWithItsRequest() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            recorder.record(
                    "payments", "RETRY", "resilience4j", "Gateway#pay", "RETRY_EXHAUSTED", 3, 40L, "IOException");
        }

        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.FAULT_TOLERANCE);
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.durationNanos()).isEqualTo(40_000_000L);
            FaultTolerancePayload payload = (FaultTolerancePayload) event.payload();
            assertThat(payload.policy()).isEqualTo("payments");
            assertThat(payload.outcome()).isEqualTo("RETRY_EXHAUSTED");
            assertThat(payload.attempt()).isEqualTo(3);
            assertThat(payload.failureCategory()).isEqualTo("IOException");
        });
    }

    @Test
    void keepsCapturingWhenAStreamSubscriberThrows() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        AtomicInteger healthyNotifications = new AtomicInteger();
        recorder.subscribe(() -> {
            throw new IllegalStateException("stream is gone");
        });
        recorder.subscribe(healthyNotifications::incrementAndGet);

        recorder.record("orders", "RETRY", "resilience4j", "Target#a", "RETRY", 1, 12L, "IOException");

        // A broken Live Activity subscriber must neither disrupt the protected call nor starve the others.
        assertThat(recorder.recent()).extracting(CapturedEvent::policyName).containsExactly("orders");
        assertThat(healthyNotifications).hasValue(1);
    }

    @Test
    void evictsOldestBeyondTheConfiguredBound() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 2);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("c", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        assertThat(recorder.recent()).extracting(CapturedEvent::policyName).containsExactly("c", "b");
        assertThat(recorder.totalCaptured()).isEqualTo(3);
    }

    @Test
    void hardCapsTheBufferRegardlessOfConfiguredSize() {
        assertThat(new FaultToleranceEventRecorder(true, Integer.MAX_VALUE).getMaxEntries())
                .isEqualTo(FaultToleranceEventRecorder.MAX_BUFFER_SIZE);
        assertThat(new FaultToleranceEventRecorder(true, 0).getMaxEntries()).isEqualTo(1);
        assertThat(new FaultToleranceEventRecorder(true, -5).getMaxEntries()).isEqualTo(1);
    }

    @Test
    void capturesNothingWhenDisabled() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(false, 10);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.recordStateTransition("cb", "resilience4j", null, "OPEN");

        assertThat(recorder.isEnabled()).isFalse();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void rejectsBlankPolicyNamesAndOutcomesRatherThanRecordingEmptyRows() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);

        recorder.record(null, "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("  ", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("a", "RETRY", "resilience4j", null, null, 1, null, null);

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void truncatesFreeTextMetadataAndNormalizesNumbers() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        String longName = "x".repeat(FaultToleranceEventRecorder.MAX_METADATA_LENGTH + 50);

        recorder.record(longName, "RETRY", "resilience4j", longName, "RETRY", -3, -20L, longName);

        CapturedEvent event = recorder.recent().get(0);
        assertThat(event.policyName()).hasSize(FaultToleranceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.target()).hasSize(FaultToleranceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.failureCategory()).hasSize(FaultToleranceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.attempt()).isEqualTo(1);
        assertThat(event.durationMillis()).isZero();
    }

    @Test
    void recordsStateTransitionsInTheirOwnFieldRatherThanTheFailureCategory() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);

        recorder.recordStateTransition("payments", "resilience4j", "PaymentClient", "OPEN");

        CapturedEvent event = recorder.recent().get(0);
        assertThat(event.outcome()).isEqualTo(FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION);
        assertThat(event.policyType()).isEqualTo(FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER);
        assertThat(event.state()).isEqualTo("OPEN");
        assertThat(event.failureCategory()).isNull();
        assertThat(event.attempt()).isNull();
    }

    @Test
    void flattensToTheStableContractWithTheRequestedCap() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-1", null));
        recorder.record("a", "RETRY", "resilience4j", "T", "RETRY", 2, 7L, "IOException");
        recorder.record("b", "RETRY", "resilience4j", "T", "RETRY", 2, 7L, "IOException");

        List<FaultToleranceEventDto> dtos = recorder.recentDtos(1);

        assertThat(dtos).hasSize(1);
        FaultToleranceEventDto dto = dtos.get(0);
        assertThat(dto.policyName()).isEqualTo("b");
        assertThat(dto.traceId()).isEqualTo("trace-1");
        assertThat(dto.id()).isEqualTo("fault-tolerance-2");
        assertThat(recorder.recentDtos(0)).hasSize(2);
    }

    @Test
    void notifiesSubscribersUntilTheyUnsubscribe() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        AtomicInteger notifications = new AtomicInteger();
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(notifications.get()).isEqualTo(1);

        unsubscribe.run();
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(notifications.get()).isEqualTo(1);
    }

    @Test
    void clearEmptiesTheBufferWithoutBreakingSubsequentCapture() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        recorder.clear();
        assertThat(recorder.recent()).isEmpty();

        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void survivesACorrelationProviderThatThrows() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("no context");
        });

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }
}
