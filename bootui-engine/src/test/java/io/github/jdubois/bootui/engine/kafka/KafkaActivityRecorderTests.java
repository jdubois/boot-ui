package io.github.jdubois.bootui.engine.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KafkaActivityRecorderTests {

    @Test
    void recordsProduceAndConsumeNewestFirst() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);

        recorder.recordProduce("orders", 0, "order-1", 5L, true, null);
        recorder.recordConsume("orders", 0, 42L, "order-1", 3L, true, null, "orders-group", "ordersListener");

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).direction()).isEqualTo(Direction.CONSUME);
        assertThat(recent.get(0).groupId()).isEqualTo("orders-group");
        assertThat(recent.get(0).listenerId()).isEqualTo("ordersListener");
        assertThat(recent.get(0).offset()).isEqualTo(42L);
        assertThat(recent.get(0).key()).isEqualTo(KafkaActivityRecorder.hashKey("order-1"));
        assertThat(recent.get(1).direction()).isEqualTo(Direction.PRODUCE);
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }

    @Test
    void disabledRecorderDropsMessages() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 10, 200);
        recorder.recordProduce("orders", 0, "order-1", 5L, true, null);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void evictsOldestWhenBufferIsFull() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 2, 200);
        recorder.recordProduce("orders", 0, "k1", 1L, true, null);
        recorder.recordProduce("orders", 0, "k2", 1L, true, null);
        recorder.recordProduce("orders", 0, "k3", 1L, true, null);

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).key()).isEqualTo(KafkaActivityRecorder.hashKey("k3"));
        assertThat(recent.get(1).key()).isEqualTo(KafkaActivityRecorder.hashKey("k2"));
        assertThat(recorder.totalCaptured()).isEqualTo(3);
    }

    @Test
    void hashesKeysInsteadOfCapturingThemRaw() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 8);
        recorder.recordProduce("orders", 0, "abcdefghij", 1L, true, null);
        assertThat(recorder.recent().get(0).key())
                .isEqualTo(KafkaActivityRecorder.hashKey("abcdefghij", 8))
                .doesNotContain("abcdefghij");
    }

    @Test
    void keyCaptureCanBeDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, false, 10, 200);
        recorder.recordProduce("orders", 0, "order-1", 1L, true, null);
        assertThat(recorder.recent().get(0).key()).isNull();
    }

    @Test
    void recordsFailureWithErrorMessage() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordProduce("orders", null, "order-1", 2L, false, "boom");
        CapturedMessage entry = recorder.recent().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorMessage()).isEqualTo("boom");
    }

    @Test
    void clearRemovesAllMessagesAndNotifiesListeners() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        AtomicInteger notifications = new AtomicInteger();
        recorder.subscribe(notifications::incrementAndGet);

        recorder.recordProduce("orders", 0, "order-1", 1L, true, null);
        assertThat(notifications.get()).isEqualTo(1);

        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(notifications.get()).isEqualTo(2);
    }

    @Test
    void subscribeReturnsHandleThatUnsubscribes() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        AtomicInteger notifications = new AtomicInteger();
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);
        unsubscribe.run();

        recorder.recordProduce("orders", 0, "order-1", 1L, true, null);
        assertThat(notifications.get()).isZero();
    }

    @Test
    void hashKeyIsStableAndDeterministic() {
        assertThat(KafkaActivityRecorder.hashKey("42")).isEqualTo(KafkaActivityRecorder.hashKey("42"));
        assertThat(KafkaActivityRecorder.hashKey("42")).isNotEqualTo(KafkaActivityRecorder.hashKey("43"));
        assertThat(KafkaActivityRecorder.hashKey("42")).hasSize(16);
    }

    // JMS-specific record methods — these delegate to the shared record() and appear identically
    // in recent() alongside Kafka entries.

    @Test
    void jmsProduceIsRecordedWithNullPartitionAndOffset() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordJmsProduce("queue/Orders", null, 12L, true, null);

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage msg = recent.get(0);
        assertThat(msg.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(msg.topic()).isEqualTo("queue/Orders");
        assertThat(msg.partition()).isNull();
        assertThat(msg.offset()).isNull();
        assertThat(msg.key()).isNull(); // messageId was null
        assertThat(msg.durationMillis()).isEqualTo(12L);
        assertThat(msg.success()).isTrue();
    }

    @Test
    void jmsProduceHashesMessageId() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordJmsProduce("orders", "ID:msg-123", 5L, true, null);

        CapturedMessage msg = recorder.recent().get(0);
        assertThat(msg.key())
                .isNotNull()
                .doesNotContain("ID:msg-123") // must be hashed
                .isEqualTo(KafkaActivityRecorder.hashKey("ID:msg-123"));
    }

    @Test
    void jmsConsumeIsRecordedWithSubscriptionNameAndListenerId() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordJmsConsume("topic/Events", "ID:evt-1", 7L, true, null, "my-sub", "ordersListener");

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage msg = recent.get(0);
        assertThat(msg.direction()).isEqualTo(Direction.CONSUME);
        assertThat(msg.topic()).isEqualTo("topic/Events");
        assertThat(msg.groupId()).isEqualTo("my-sub");
        assertThat(msg.listenerId()).isEqualTo("ordersListener");
        assertThat(msg.durationMillis()).isEqualTo(7L);
        assertThat(msg.success()).isTrue();
    }

    @Test
    void jmsConsumeFailureIsRecorded() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordJmsConsume("orders", null, 3L, false, "delivery failed", null, "myFactory");

        CapturedMessage msg = recorder.recent().get(0);
        assertThat(msg.success()).isFalse();
        assertThat(msg.errorMessage()).isEqualTo("delivery failed");
    }

    @Test
    void jmsAndKafkaEntriesShareTheSameBuffer() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 200);
        recorder.recordProduce("kafka-topic", 0, "k1", 1L, true, null);
        recorder.recordJmsProduce("jms-queue", null, 2L, true, null);

        assertThat(recorder.recent()).hasSize(2);
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }
}
