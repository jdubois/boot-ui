package io.github.jdubois.bootui.engine.jms;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.Direction;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JmsActivityRecorderTests {

    @Test
    void recordsProduceAndConsumeNewestFirst() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);

        recorder.recordProduce("orders", "ID:send-1", 5L, true, null);
        recorder.recordConsume("events", "ID:receive-1", 3L, false, "IllegalStateException", "updates", "listener");

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).direction()).isEqualTo(Direction.CONSUME);
        assertThat(recent.get(0).destination()).isEqualTo("events");
        assertThat(recent.get(0).messageId())
                .isEqualTo(JmsActivityRecorder.hashMessageId("ID:receive-1"))
                .doesNotContain("ID:receive-1");
        assertThat(recent.get(0).failureType()).isEqualTo("IllegalStateException");
        assertThat(recent.get(0).subscriptionName()).isEqualTo("updates");
        assertThat(recent.get(0).listenerId()).isEqualTo("listener");
        assertThat(recent.get(1).direction()).isEqualTo(Direction.PRODUCE);
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }

    @Test
    void disabledRecorderDropsMessages() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(false, true, 10, 16);

        recorder.recordProduce("orders", "ID:1", 5L, true, null);

        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void messageIdCaptureCanBeDisabled() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, false, 10, 16);

        recorder.recordProduce("orders", "ID:1", 5L, true, null);

        assertThat(recorder.recent().get(0).messageId()).isNull();
    }

    @Test
    void appliesConfiguredMessageIdHashLength() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 12);

        recorder.recordProduce("orders", "ID:1", 5L, true, null);

        assertThat(recorder.recent().get(0).messageId()).hasSize(12);
    }

    @Test
    void evictsOldestAndBoundsMetadata() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 1, 16);
        String longDestination = "x".repeat(250);

        recorder.recordProduce("old", null, 1L, true, null);
        recorder.recordProduce(longDestination, null, -1L, true, null);

        assertThat(recorder.recent()).singleElement().satisfies(message -> {
            assertThat(message.destination()).hasSize(200);
            assertThat(message.durationMillis()).isZero();
        });
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }

    @Test
    void clearAndSubscriptionsAreIsolated() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        AtomicInteger notifications = new AtomicInteger();
        recorder.subscribe(() -> {
            throw new IllegalStateException("subscriber failure");
        });
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.recordProduce("orders", null, 1L, true, null);
        recorder.clear();
        unsubscribe.run();
        recorder.recordProduce("orders", null, 1L, true, null);

        assertThat(notifications.get()).isEqualTo(2);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void mapsJmsMetadataWithoutKafkaFieldNames() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        recorder.recordConsume("orders", "ID:1", 4L, false, "JMSException", null, "listener");

        var entry = JmsActivityEntries.toEntry(recorder.recent().get(0));

        assertThat(entry.id()).startsWith("jms-");
        assertThat(entry.type()).isEqualTo("MESSAGING");
        assertThat(entry.summary()).isEqualTo("← orders");
        assertThat(entry.detail())
                .contains("messageId=")
                .contains("JMSException")
                .doesNotContain("key=");
        assertThat(entry.durationMs()).isEqualTo(4L);
        assertThat(entry.severity()).isEqualTo("ERROR");
    }

    @Test
    void mapsDedicatedPanelReportFromTheSameBuffer() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        recorder.recordConsume("orders", "ID:1", 4L, false, "JMSException", "updates", "listener");

        var report = JmsMessageDtos.toReport(recorder);

        assertThat(report.available()).isTrue();
        assertThat(report.capturing()).isTrue();
        assertThat(report.captureMessageIdEnabled()).isTrue();
        assertThat(report.maxEntries()).isEqualTo(10);
        assertThat(report.totalCaptured()).isEqualTo(1);
        assertThat(report.messages()).singleElement().satisfies(message -> {
            assertThat(message.direction()).isEqualTo("CONSUME");
            assertThat(message.destination()).isEqualTo("orders");
            assertThat(message.messageId()).hasSize(16);
            assertThat(message.failureType()).isEqualTo("JMSException");
            assertThat(message.subscriptionName()).isEqualTo("updates");
            assertThat(message.listenerId()).isEqualTo("listener");
        });
    }
}
