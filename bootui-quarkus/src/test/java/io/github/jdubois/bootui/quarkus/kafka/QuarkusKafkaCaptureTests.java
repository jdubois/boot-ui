package io.github.jdubois.bootui.quarkus.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the two SmallRye Reactive Messaging Kafka capture interceptors. They construct real
 * {@link OutgoingKafkaRecordMetadata}/{@link IncomingKafkaRecordMetadata} on real MicroProfile
 * {@link Message}s and drive the interceptor callbacks directly, asserting on the shared
 * {@link KafkaActivityRecorder}'s captured view. No broker, connector, or CDI container is involved.
 */
class QuarkusKafkaCaptureTests {

    private static KafkaActivityRecorder enabledRecorder() {
        return new KafkaActivityRecorder(true, true, 200, 16);
    }

    // ---- producer (OutgoingInterceptor) ----------------------------------------------------------

    @Test
    void producerRecordsSuccessfulKafkaSendWithNullDuration() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        capture.onMessageAck(outgoingKafkaMessage("orders", 2, "key-1"));

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage message = recent.get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.partition()).isEqualTo(2);
        // Keys are hashed (SHA-256, truncated to 16 hex chars) before capture; this is hashKey("key-1").
        assertThat(message.key()).isEqualTo("be2974546978e373");
        assertThat(message.offset()).isNull();
        assertThat(message.durationMillis()).isNull();
        assertThat(message.success()).isTrue();
        assertThat(message.errorMessage()).isNull();
    }

    @Test
    void producerRecordsFailedKafkaSendWithErrorMessage() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        capture.onMessageNack(outgoingKafkaMessage("orders", 0, "key-1"), new IllegalStateException("boom"));

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage message = recent.get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("Message processing failed");
    }

    @Test
    void producerMapsUnassignedPartitionToNull() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        capture.onMessageAck(outgoingKafkaMessage("orders", -1, null));

        assertThat(recorder.recent()).singleElement().satisfies(message -> {
            assertThat(message.partition()).isNull();
            assertThat(message.key()).isNull();
        });
    }

    @Test
    void producerIgnoresNonKafkaMessage() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        capture.onMessageAck(Message.of("payload"));
        capture.onMessageNack(Message.of("payload"), new RuntimeException("x"));

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void producerShortCircuitsWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 200, 16);
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        capture.onMessageAck(outgoingKafkaMessage("orders", 1, "key-1"));

        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void producerFailsOpenWhenMetadataInspectionThrows() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaProducerCapture capture = new QuarkusKafkaProducerCapture(recorder);

        // Must not propagate the exception (fail-open); nothing is recorded.
        capture.onMessageAck(throwingMessage());

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void producerPrioritySoAppInterceptorAlwaysWins() {
        assertThat(new QuarkusKafkaProducerCapture(enabledRecorder()).getPriority())
                .isEqualTo(Integer.MAX_VALUE);
    }

    // ---- consumer (IncomingInterceptor) ----------------------------------------------------------

    @Test
    void consumerRecordsSuccessfulKafkaDeliveryWithTimingAndChannel() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        Message<?> received = capture.afterMessageReceive(incomingKafkaMessage("orders", 3, 42L, "key-2", "orders-in"));
        capture.onMessageAck(received);

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage message = recent.get(0);
        assertThat(message.direction()).isEqualTo(Direction.CONSUME);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.partition()).isEqualTo(3);
        assertThat(message.offset()).isEqualTo(42L);
        // Keys are hashed (SHA-256, truncated to 16 hex chars) before capture; this is hashKey("key-2").
        assertThat(message.key()).isEqualTo("7c36b0a9dedde119");
        assertThat(message.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(message.success()).isTrue();
        assertThat(message.errorMessage()).isNull();
        assertThat(message.groupId()).isNull();
        assertThat(message.listenerId()).isEqualTo("orders-in");
    }

    @Test
    void eachDeliveryIsRecordedAsItsOwnExecutionAndASendAsItsSendersChild() throws Exception {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture consumer = new QuarkusKafkaConsumerCapture(recorder);
        QuarkusKafkaProducerCapture producer = new QuarkusKafkaProducerCapture(recorder);

        Message<?> first = consumer.afterMessageReceive(incomingKafkaMessage("orders", 0, 1L, "k", "orders-in"));
        Message<?> second = consumer.afterMessageReceive(incomingKafkaMessage("orders", 0, 2L, "k", "orders-in"));
        consumer.onMessageAck(first);
        consumer.onMessageNack(second, new IllegalStateException("boom"));
        Message<?> sent;
        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forExecution("00112233aabbccdd"))) {
            sent = producer.onMessage(outgoingKafkaMessage("shipments", 0, "k"));
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> producer.onMessageAck(sent))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        List<CapturedMessage> consumed = recorder.recent().stream()
                .filter(message -> message.direction() == Direction.CONSUME)
                .toList();
        assertThat(consumed)
                .extracting(CapturedMessage::executionId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
        assertThat(recorder.recent())
                .filteredOn(message -> message.direction() == Direction.PRODUCE)
                .singleElement()
                .extracting(CapturedMessage::executionId)
                .isEqualTo("00112233aabbccdd");
    }

    @Test
    void aDeliveryCarryingATraceparentLinksItsJournalEventToTheSendersTrace() {
        KafkaActivityRecorder recorder = enabledRecorder();
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 0, 1L, "k", "payload");
        record.headers()
                .add(
                        "traceparent",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8));

        capture.onMessageAck(capture.afterMessageReceive(
                Message.of("payload", Metadata.of(new IncomingKafkaRecordMetadata<>(record, "orders-in")))));

        assertThat(published)
                .singleElement()
                .satisfies(event -> assertThat(((MessagingPayload) event.payload()).linkedTraceId())
                        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    void consumerRecordsFailedKafkaDeliveryWithErrorMessage() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        Message<?> received = capture.afterMessageReceive(incomingKafkaMessage("orders", 3, 42L, "key-2", "orders-in"));
        capture.onMessageNack(received, new IllegalStateException("processing failed"));

        List<CapturedMessage> recent = recorder.recent();
        assertThat(recent).hasSize(1);
        CapturedMessage message = recent.get(0);
        assertThat(message.direction()).isEqualTo(Direction.CONSUME);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("Message processing failed");
    }

    @Test
    void consumerLeavesNonKafkaMessageUntouched() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        Message<String> original = Message.of("payload");
        Message<?> received = capture.afterMessageReceive(original);
        assertThat(received).isSameAs(original);

        capture.onMessageAck(received);
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void consumerShortCircuitsWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 200, 16);
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        Message<?> incoming = incomingKafkaMessage("orders", 3, 42L, "key-2", "orders-in");
        Message<?> received = capture.afterMessageReceive(incoming);
        assertThat(received).isSameAs(incoming);

        capture.onMessageAck(received);
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void consumerFailsOpenWhenMetadataInspectionThrows() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        Message<?> received = capture.afterMessageReceive(throwingMessage());
        assertThat(received).isNotNull();
        capture.onMessageAck(throwingMessage());

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void consumerRecordsNullDurationWhenNotTimed() {
        KafkaActivityRecorder recorder = enabledRecorder();
        QuarkusKafkaConsumerCapture capture = new QuarkusKafkaConsumerCapture(recorder);

        // Ack without a preceding afterMessageReceive (no CaptureStart marker): duration stays null.
        capture.onMessageAck(incomingKafkaMessage("orders", 3, 42L, "key-2", "orders-in"));

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(message -> assertThat(message.durationMillis()).isNull());
    }

    @Test
    void consumerPrioritySoAppInterceptorAlwaysWins() {
        assertThat(new QuarkusKafkaConsumerCapture(enabledRecorder()).getPriority())
                .isEqualTo(Integer.MAX_VALUE);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Message<String> outgoingKafkaMessage(String topic, int partition, String key) {
        OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withTopic(topic)
                .withPartition(partition)
                .withKey(key)
                .build();
        return Message.of("payload", Metadata.of(metadata));
    }

    private static Message<String> incomingKafkaMessage(
            String topic, int partition, long offset, String key, String channel) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, partition, offset, key, "payload");
        IncomingKafkaRecordMetadata<String, String> metadata = new IncomingKafkaRecordMetadata<>(record, channel);
        return Message.of("payload", Metadata.of(metadata));
    }

    /** A message whose metadata lookup throws, to exercise the interceptors' fail-open behavior. */
    private static Message<String> throwingMessage() {
        return new Message<>() {
            @Override
            public String getPayload() {
                return "payload";
            }

            @Override
            public <M> Optional<M> getMetadata(Class<? extends M> clazz) {
                throw new IllegalStateException("metadata lookup blew up");
            }
        };
    }
}
