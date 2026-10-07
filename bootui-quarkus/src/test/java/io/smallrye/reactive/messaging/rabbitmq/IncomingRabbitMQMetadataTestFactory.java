package io.smallrye.reactive.messaging.rabbitmq;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Envelope;
import io.vertx.rabbitmq.RabbitMQMessage;

public final class IncomingRabbitMQMetadataTestFactory {

    private IncomingRabbitMQMetadataTestFactory() {}

    public static IncomingRabbitMQMetadata create(BasicProperties properties, Envelope envelope) {
        RabbitMQMessage message = mock(RabbitMQMessage.class);
        when(message.properties()).thenReturn(properties);
        when(message.envelope()).thenReturn(envelope);
        return new IncomingRabbitMQMetadata(message);
    }
}
