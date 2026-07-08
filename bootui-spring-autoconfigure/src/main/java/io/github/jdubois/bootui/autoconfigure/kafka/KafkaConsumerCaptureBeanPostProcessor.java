package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Wraps every {@link AbstractKafkaListenerContainerFactory} bean's {@link RecordInterceptor} after
 * initialization so every {@code @KafkaListener} delivery is recorded into {@link
 * KafkaActivityRecorder} before delegating to whatever interceptor (or none) the application already
 * had configured — the consume-side twin of {@link KafkaProducerCaptureBeanPostProcessor}.
 *
 * <p>The factory has no public getter for its current {@code recordInterceptor} (only {@code
 * setRecordInterceptor}), so it is read via {@link DirectFieldAccessor} and composed rather than
 * replaced, exactly like the producer-side post-processor. Reading the field is best-effort: if it
 * fails, the capturing interceptor is still installed so deliveries are never silently left
 * uncaptured, and the application's own interceptor is never dropped.</p>
 */
public final class KafkaConsumerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerCaptureBeanPostProcessor.class);

    private final ObjectProvider<KafkaActivityRecorder> recorderProvider;

    public KafkaConsumerCaptureBeanPostProcessor(ObjectProvider<KafkaActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory)) {
            return bean;
        }
        KafkaActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            @SuppressWarnings("unchecked")
            RecordInterceptor<Object, Object> existing = (RecordInterceptor<Object, Object>)
                    new DirectFieldAccessor(factory).getPropertyValue("recordInterceptor");
            @SuppressWarnings({"unchecked", "rawtypes"})
            AbstractKafkaListenerContainerFactory untyped = (AbstractKafkaListenerContainerFactory) factory;
            untyped.setRecordInterceptor(new CapturingRecordInterceptor(existing, recorder, beanName));
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable Kafka consumer capture for listener container factory bean "
                            + "'{}'; leaving it unwrapped",
                    beanName,
                    ex);
        }
        return bean;
    }

    /**
     * Times each delivery from {@link #intercept} to its terminal {@link #success}/{@link #failure}
     * callback (both are always invoked on the same thread, synchronously, by the listener container),
     * records the outcome, then delegates to the composed interceptor.
     */
    private static final class CapturingRecordInterceptor implements RecordInterceptor<Object, Object> {

        private final RecordInterceptor<Object, Object> delegate;
        private final KafkaActivityRecorder recorder;
        private final String listenerId;
        private final ThreadLocal<Long> startNanos = new ThreadLocal<>();

        private CapturingRecordInterceptor(
                RecordInterceptor<Object, Object> delegate, KafkaActivityRecorder recorder, String listenerId) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.listenerId = listenerId;
        }

        @Override
        public ConsumerRecord<Object, Object> intercept(
                ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            startNanos.set(System.nanoTime());
            return delegate == null ? record : delegate.intercept(record, consumer);
        }

        @Override
        public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            recordOutcome(record, consumer, true, null);
            if (delegate != null) {
                delegate.success(record, consumer);
            }
        }

        @Override
        public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
            recordOutcome(record, consumer, false, exception == null ? null : exception.getMessage());
            if (delegate != null) {
                delegate.failure(record, exception, consumer);
            }
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            startNanos.remove();
            if (delegate != null) {
                delegate.afterRecord(record, consumer);
            }
        }

        private void recordOutcome(
                ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer, boolean success, String errorMessage) {
            Long start = startNanos.get();
            long durationMillis = start == null ? 0L : (System.nanoTime() - start) / 1_000_000L;
            Object key = record.key();
            String groupId = groupIdOf(consumer);
            recorder.recordConsume(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    key == null ? null : String.valueOf(key),
                    durationMillis,
                    success,
                    errorMessage,
                    groupId,
                    listenerId);
        }

        private static String groupIdOf(Consumer<Object, Object> consumer) {
            try {
                return consumer.groupMetadata().groupId();
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
