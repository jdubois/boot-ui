package io.github.jdubois.bootui.engine.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.Direction;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RabbitActivityRecorderTests {

    @Test
    void retainsOnlyBoundedMetadataNewestFirst() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 2, 16);

        recorder.recordPublish("orders", "created", null, true, null, "secret-correlation");
        recorder.recordConsume("orders", "created", "workers", -1L, false, "payload=secret", "secret-correlation");
        recorder.recordPublish("billing", "charged", 3L, true, null, "secret-correlation");

        assertThat(recorder.totalCaptured()).isEqualTo(3);
        assertThat(recorder.recent()).extracting(CapturedMessage::exchange).containsExactly("billing", "orders");
        assertThat(recorder.recent().get(1)).satisfies(message -> {
            assertThat(message.direction()).isEqualTo(Direction.CONSUME);
            assertThat(message.durationMillis()).isZero();
            assertThat(message.errorMessage()).isEqualTo("Message processing failed");
            assertThat(message.errorMessage()).doesNotContain("secret");
            assertThat(message.correlationId()).isNull();
        });
    }

    @Test
    void hashesCorrelationIdsOnlyWhenOptedInAndHonorsConfiguredLength() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 1, 32);

        recorder.recordPublish("", "orders", null, true, null, "customer-123");

        assertThat(recorder.recent().get(0).correlationId()).hasSize(32).isNotEqualTo("customer-123");
    }

    @Test
    void boundsEveryRetainedTopologyValue() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 1, 16);
        String oversized = "x".repeat(2_000);

        recorder.recordConsume(oversized, oversized, oversized, 1L, true, null, null);

        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.exchange()).hasSize(512);
        assertThat(message.routingKey()).hasSize(512);
        assertThat(message.queue()).hasSize(512);
    }

    @Test
    void disabledRecorderDoesNotCaptureOrNotify() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(false, true, 10, 16);
        AtomicInteger notifications = new AtomicInteger();
        recorder.subscribe(notifications::incrementAndGet);

        recorder.recordPublish("orders", "created", null, true, null, "correlation");

        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
        assertThat(notifications).hasValue(0);
    }

    @Test
    void subscriptionsAreRemovableAndListenerFailuresAreIsolated() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 10, 16);
        AtomicInteger notifications = new AtomicInteger();
        recorder.subscribe(() -> {
            throw new IllegalStateException("subscriber failure");
        });
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.recordPublish("orders", "created", null, true, null, null);
        unsubscribe.run();
        recorder.clear();

        assertThat(notifications).hasValue(1);
        assertThat(recorder.recent()).isEmpty();
    }
}
