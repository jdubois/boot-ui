package io.github.jdubois.bootui.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));

        Object bean = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(bean, "someBean"))
                .isSameAs(bean);
    }

    @Test
    void skipsWrappingWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 10, 16);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        postProcessor.postProcessAfterInitialization(factory, "kafkaListenerContainerFactory");

        assertThat(currentInterceptor(factory)).isNull();
    }

    @Test
    void capturesSuccessfulDeliveryAndComposesWithExistingInterceptor() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
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
        ConsumerGroupMetadata groupMetadata = groupMetadata("group-a");
        when(consumer.groupMetadata()).thenReturn(groupMetadata);

        interceptor.intercept(record, consumer);
        interceptor.success(record, consumer);
        interceptor.afterRecord(record, consumer);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.direction()).isEqualTo(Direction.CONSUME);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.offset()).isEqualTo(5L);
        assertThat(message.key()).isEqualTo(hashedKey("k1"));
        assertThat(message.groupId()).isEqualTo("group-a");
        assertThat(message.listenerId()).isEqualTo("myListenerFactory");
        assertThat(message.success()).isTrue();
        verify(existing).intercept(record, consumer);
        verify(existing).success(record, consumer);
        verify(existing).afterRecord(record, consumer);
    }

    @Test
    void eachDeliveryIsAnExecutionOfItsOwnFromInterceptToAfterRecord() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");
        RecordInterceptor<Object, Object> interceptor = currentInterceptor(factory);
        Consumer<Object, Object> consumer = mock(Consumer.class);
        List<String> seen = new ArrayList<>();

        for (long offset = 1; offset <= 2; offset++) {
            ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders", 0, offset, "k", "v");
            interceptor.intercept(record, consumer);
            seen.add(BootUiCorrelation.current().executionId());
            interceptor.success(record, consumer);
            interceptor.afterRecord(record, consumer);
            assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
        }

        assertThat(seen)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
        assertThat(recorder.recent())
                .extracting(CapturedMessage::executionId)
                .containsExactlyInAnyOrderElementsOf(seen);
    }

    @Test
    void aDeliveryCarryingATraceparentLinksItsExecutionAndItsJournalEventToTheSendersTrace() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        List<RuntimeEvent> published = new ArrayList<>();
        recorder.setRuntimeEventSink(published::add);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");
        RecordInterceptor<Object, Object> interceptor = currentInterceptor(factory);
        Consumer<Object, Object> consumer = mock(Consumer.class);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders", 0, 1L, "k", "v");
        record.headers()
                .add(
                        "traceparent",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, consumer);
        String linked = BootUiCorrelation.current().linkedTraceId();
        interceptor.success(record, consumer);
        interceptor.afterRecord(record, consumer);

        assertThat(linked).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(published)
                .singleElement()
                .satisfies(event -> assertThat(((MessagingPayload) event.payload()).linkedTraceId())
                        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    void aRecordTheApplicationsInterceptorFiltersOutLeavesNoExecutionOpen() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        @SuppressWarnings("unchecked")
        RecordInterceptor<Object, Object> filtering = mock(RecordInterceptor.class);
        when(filtering.intercept(any(), any())).thenReturn(null);
        factory.setRecordInterceptor(filtering);
        new KafkaConsumerCaptureBeanPostProcessor(provider(recorder))
                .postProcessAfterInitialization(factory, "myListenerFactory");

        currentInterceptor(factory).intercept(new ConsumerRecord<>("orders", 0, 1L, "k", "v"), mock(Consumer.class));

        assertThat(BootUiCorrelation.current()).isSameAs(CorrelationContext.NONE);
    }

    @Test
    void capturesFailedDelivery() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        KafkaConsumerCaptureBeanPostProcessor postProcessor =
                new KafkaConsumerCaptureBeanPostProcessor(provider(recorder));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        postProcessor.postProcessAfterInitialization(factory, "myListenerFactory");

        RecordInterceptor<Object, Object> interceptor = currentInterceptor(factory);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders", 0, 5L, "k1", "v1");
        Consumer<Object, Object> consumer = mock(Consumer.class);
        ConsumerGroupMetadata groupMetadata = groupMetadata("group-a");
        when(consumer.groupMetadata()).thenReturn(groupMetadata);

        interceptor.intercept(record, consumer);
        interceptor.failure(record, new IllegalStateException("boom"), consumer);
        interceptor.afterRecord(record, consumer);

        assertThat(recorder.recent()).hasSize(1);
        CapturedMessage message = recorder.recent().get(0);
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("Message processing failed");
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

    private static String hashedKey(String key) {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 1, 16);
        recorder.recordConsume("orders", 0, 0L, key, 0L, true, null, null, null);
        return recorder.recent().get(0).key();
    }

    // ConsumerGroupMetadata's own constructors are deprecated for removal (Kafka 4.2+); a mock sidesteps them
    // since the only member the capture code reads is groupId().
    private static ConsumerGroupMetadata groupMetadata(String groupId) {
        ConsumerGroupMetadata metadata = mock(ConsumerGroupMetadata.class);
        when(metadata.groupId()).thenReturn(groupId);
        return metadata;
    }
}
