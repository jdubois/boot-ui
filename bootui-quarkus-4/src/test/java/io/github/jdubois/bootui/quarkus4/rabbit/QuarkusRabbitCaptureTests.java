package io.github.jdubois.bootui.quarkus4.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.Direction;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.inject.Instance;
import java.util.Optional;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

class QuarkusRabbitCaptureTests {

    @Test
    @SuppressWarnings("unchecked")
    void hooksCreatedBeforeFreezeResolveNoRecorderUntilAnAdmittedCallback() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        Instance<RabbitActivityRecorder> instance = mock(Instance.class);
        when(instance.get()).thenReturn(recorder);
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            QuarkusRabbitProducerCapture producer = new QuarkusRabbitProducerCapture(instance);
            QuarkusRabbitConsumerCapture consumer = new QuarkusRabbitConsumerCapture(instance);
            assertThat(producer.beforeMessageSend(null)).isNull();
            assertThat(consumer.afterMessageReceive(null)).isNull();
            producer.onMessageAck(null);
            producer.onMessageNack(null, null);
            consumer.onMessageAck(null);
            consumer.onMessageNack(null, null);
            verifyNoInteractions(instance);

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            producer.onMessageAck(outgoingMessage("created", "customer-123"));
            assertThat(recorder.recent()).hasSize(1);
        }
    }

    @Test
    void producerCapturesAckAndNackWithoutPayload() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        QuarkusRabbitProducerCapture capture = new QuarkusRabbitProducerCapture(recorder);

        capture.onMessageAck(outgoingMessage("created", "customer-123"));
        capture.onMessageNack(outgoingMessage("failed", "customer-456"), new IllegalStateException("payload=secret"));

        assertThat(recorder.recent()).hasSize(2);
        assertThat(recorder.recent().get(0)).satisfies(message -> {
            assertThat(message.direction()).isEqualTo(Direction.PUBLISH);
            assertThat(message.exchange()).isEqualTo("orders");
            assertThat(message.routingKey()).isEqualTo("failed");
            assertThat(message.success()).isFalse();
            assertThat(message.errorMessage()).isEqualTo("Message processing failed");
            assertThat(message.toString()).doesNotContain("payload=secret");
        });
        assertThat(recorder.recent().get(1).correlationId()).isNotBlank().isNotEqualTo("customer-123");
    }

    @Test
    void aPublishIsTimedFromEnteringTheChannelToItsAck() throws InterruptedException {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        QuarkusRabbitProducerCapture capture = new QuarkusRabbitProducerCapture(recorder);

        Message<?> sent = capture.beforeMessageSend(outgoingMessage("created", "customer-123"));
        Thread.sleep(20);
        capture.onMessageAck(sent);

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(message -> assertThat(message.durationMillis()).isGreaterThanOrEqualTo(20L));
    }

    @Test
    void consumerTimesAckAndPreservesMetadataOnly() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        QuarkusRabbitConsumerCapture capture = new QuarkusRabbitConsumerCapture(recorder);

        Message<?> received = capture.afterMessageReceive(incomingMessage("orders", "created", "customer-123"));
        capture.onMessageAck(received);

        assertThat(recorder.recent()).singleElement().satisfies(message -> {
            assertThat(message.direction()).isEqualTo(Direction.CONSUME);
            assertThat(message.exchange()).isEqualTo("orders");
            assertThat(message.routingKey()).isEqualTo("created");
            assertThat(message.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(message.correlationId()).isNotBlank().isNotEqualTo("customer-123");
            assertThat(message.toString()).doesNotContain("sensitive payload");
        });
    }

    @Test
    void ignoresNonRabbitMessagesAndFailsOpen() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 10, 16);
        QuarkusRabbitProducerCapture producer = new QuarkusRabbitProducerCapture(recorder);
        QuarkusRabbitConsumerCapture consumer = new QuarkusRabbitConsumerCapture(recorder);
        Message<String> plain = Message.of("payload");

        producer.onMessageAck(plain);
        assertThat(consumer.afterMessageReceive(plain)).isSameAs(plain);
        consumer.onMessageAck(plain);
        producer.onMessageAck(throwingMessage());
        consumer.onMessageAck(throwingMessage());

        assertThat(recorder.recent()).isEmpty();
        assertThat(producer.getPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(consumer.getPriority()).isEqualTo(Integer.MAX_VALUE);
    }

    private static Message<String> outgoingMessage(String routingKey, String correlationId) {
        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withExchange("orders")
                .withRoutingKey(routingKey)
                .withCorrelationId(correlationId)
                .build();
        return Message.of("sensitive payload", Metadata.of(metadata));
    }

    private static Message<String> incomingMessage(String exchange, String routingKey, String correlationId) {
        AMQP.BasicProperties properties =
                new AMQP.BasicProperties.Builder().correlationId(correlationId).build();
        Envelope envelope = new Envelope(1L, false, exchange, routingKey);
        IncomingRabbitMQMetadata metadata = new IncomingRabbitMQMetadata(envelope, properties);
        return Message.of("sensitive payload", Metadata.of(metadata));
    }

    private static Message<String> throwingMessage() {
        return new Message<>() {
            @Override
            public String getPayload() {
                return "payload";
            }

            @Override
            public <M> Optional<M> getMetadata(Class<? extends M> clazz) {
                throw new IllegalStateException("metadata lookup failed");
            }
        };
    }
}
