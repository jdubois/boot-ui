package io.github.jdubois.bootui.autoconfigure.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

class RabbitProducerCaptureBeanPostProcessorTests {

    @Test
    void composesWithExistingProcessorAndNeverReadsThePayload() {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        RabbitTemplate template = new RabbitTemplate();
        MessagePostProcessor existing = mock(MessagePostProcessor.class);
        template.addBeforePublishPostProcessors(existing);
        RabbitProducerCaptureBeanPostProcessor processor =
                new RabbitProducerCaptureBeanPostProcessor(provider(recorder));
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId("customer-123");
        Message message = new Message("sensitive payload".getBytes(), properties);

        processor.postProcessAfterInitialization(template, "rabbitTemplate");
        for (MessagePostProcessor postProcessor : template.getBeforePublishPostProcessors()) {
            postProcessor.postProcessMessage(message, null, "orders", "created");
        }

        verify(existing).postProcessMessage(message, null, "orders", "created");
        assertThat(recorder.recent()).singleElement().satisfies(captured -> {
            assertThat(captured.exchange()).isEqualTo("orders");
            assertThat(captured.routingKey()).isEqualTo("created");
            assertThat(captured.correlationId()).isNotEqualTo("customer-123");
            assertThat(captured.toString()).doesNotContain("sensitive payload");
        });
    }

    @Test
    void leavesTemplatesUntouchedWhenCaptureIsDisabled() {
        RabbitTemplate template = new RabbitTemplate();
        RabbitProducerCaptureBeanPostProcessor processor =
                new RabbitProducerCaptureBeanPostProcessor(provider(new RabbitActivityRecorder(false, false, 10, 16)));

        processor.postProcessAfterInitialization(template, "rabbitTemplate");

        assertThat(template.getBeforePublishPostProcessors()).isNull();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
