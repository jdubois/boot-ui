package io.github.jdubois.bootui.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;

class KafkaProducerCaptureBeanPostProcessorTests {

    @SuppressWarnings("unchecked")
    private final ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);

    @Test
    void ignoresBeansThatAreNotKafkaTemplates() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaProducerCaptureBeanPostProcessor postProcessor =
                new KafkaProducerCaptureBeanPostProcessor(provider(recorder));

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 10, 50);
        KafkaProducerCaptureBeanPostProcessor postProcessor =
                new KafkaProducerCaptureBeanPostProcessor(provider(recorder));
        KafkaTemplate<Object, Object> template = new KafkaTemplate<>(producerFactory);

        postProcessor.postProcessAfterInitialization(template, "kafkaTemplate");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "k1", "v1");
        ProducerListener<Object, Object> listener = currentListener(template);
        listener.onSuccess(record, metadataFor(record));
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void capturesSuccessfulSendAndComposesWithExistingListener() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaProducerCaptureBeanPostProcessor postProcessor =
                new KafkaProducerCaptureBeanPostProcessor(provider(recorder));
        KafkaTemplate<Object, Object> template = new KafkaTemplate<>(producerFactory);
        ProducerListener<Object, Object> existing = mock(ProducerListener.class);
        template.setProducerListener(existing);

        postProcessor.postProcessAfterInitialization(template, "kafkaTemplate");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "k1", "v1");
        RecordMetadata metadata = metadataFor(record);
        currentListener(template).onSuccess(record, metadata);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.PRODUCE);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.key()).isEqualTo("k1");
        assertThat(message.success()).isTrue();
        verify(existing).onSuccess(record, metadata);
    }

    @Test
    void capturesFailedSend() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 50);
        KafkaProducerCaptureBeanPostProcessor postProcessor =
                new KafkaProducerCaptureBeanPostProcessor(provider(recorder));
        KafkaTemplate<Object, Object> template = new KafkaTemplate<>(producerFactory);

        postProcessor.postProcessAfterInitialization(template, "kafkaTemplate");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "k1", "v1");
        currentListener(template).onError(record, null, new IllegalStateException("boom"));

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("boom");
    }

    @Test
    void doesNotWrapWhenRecorderUnavailable() {
        KafkaProducerCaptureBeanPostProcessor postProcessor = new KafkaProducerCaptureBeanPostProcessor(provider(null));
        KafkaTemplate<Object, Object> template = new KafkaTemplate<>(producerFactory);

        Object result = postProcessor.postProcessAfterInitialization(template, "kafkaTemplate");

        assertThat(result).isSameAs(template);
        assertThat(new org.springframework.beans.DirectFieldAccessor(template).getPropertyValue("producerListener"))
                .isInstanceOf(org.springframework.kafka.support.LoggingProducerListener.class);
    }

    @SuppressWarnings("unchecked")
    private static ProducerListener<Object, Object> currentListener(KafkaTemplate<Object, Object> template) {
        return (ProducerListener<Object, Object>)
                new org.springframework.beans.DirectFieldAccessor(template).getPropertyValue("producerListener");
    }

    private static RecordMetadata metadataFor(ProducerRecord<Object, Object> record) {
        return new RecordMetadata(new TopicPartition(record.topic(), 0), 0L, 0, 0L, 0, 0);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
