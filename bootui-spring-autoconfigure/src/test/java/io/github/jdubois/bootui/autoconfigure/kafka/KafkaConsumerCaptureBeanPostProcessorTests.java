package io.github.jdubois.bootui.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

class KafkaConsumerCaptureBeanPostProcessorTests {

    @Test
    void ignoresBeansThatAreNotListenerContainerFactories() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 10, 50);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        postProcessor.postProcessAfterInitialization(factory, "kafkaListenerContainerFactory");

        assertThat(currentInterceptor(factory)).isNull();
    }

    @Test
    void capturesSuccessfulDeliveryAndComposesWithExistingInterceptor() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        @SuppressWarnings("unchecked")
        RecordInterceptor<Object, Object> existing = mock(RecordInterceptor.class);
        factory.setRecordInterceptor(existing);

        postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");

        RecordInterceptor<Object, Object> interceptor = currentInterceptor(factory);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders", 0, 5L, "k1", "v1");
        Consumer<Object, Object> consumer = mock(Consumer.class);
        when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("group-a"));

        interceptor.intercept(record, consumer);
        interceptor.success(record, consumer);
        interceptor.afterRecord(record, consumer);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.CONSUME);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.offset()).isEqualTo(5L);
        assertThat(message.key()).isEqualTo("k1");
        assertThat(message.groupId()).isEqualTo("group-a");
        assertThat(message.listenerId()).isEqualTo("myListenerFactory");
        assertThat(message.success()).isTrue();
        verify(existing).intercept(record, consumer);
        verify(existing).success(record, consumer);
        verify(existing).afterRecord(record, consumer);
    }

    @Test
    void capturesFailedDelivery() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");

        RecordInterceptor<Object, Object> interceptor = currentInterceptor(factory);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders", 0, 5L, "k1", "v1");
        Consumer<Object, Object> consumer = mock(Consumer.class);
        when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("group-a"));

        interceptor.intercept(record, consumer);
        interceptor.failure(record, new IllegalStateException("boom"), consumer);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("boom");
    }

    @Test
    void doesNotWrapWhenRecorderUnavailable() {
        KafkaConsumerCaptureBeanPostProcessor postProcessor = new KafkaConsumerCaptureBeanPostProcessor(provider(null));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        Object result = postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");

        assertThat(result).isSameAs(factory);
        assertThat(currentInterceptor(factory)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static RecordInterceptor<Object, Object> currentInterceptor(
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory) {
        return (RecordInterceptor<Object, Object>)
                new DirectFieldAccessor(factory).getPropertyValue("recordInterceptor");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
