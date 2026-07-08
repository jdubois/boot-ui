package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;

/**
 * Wraps every {@link KafkaTemplate} bean's {@link ProducerListener} after initialization so every send
 * is recorded into {@link KafkaActivityRecorder} before delegating to whatever listener (or the
 * framework default) the application already had configured — pass-through by default, exactly like
 * {@code SqlTraceDataSourceBeanPostProcessor} wraps {@code DataSource} beans.
 *
 * <p>{@code KafkaTemplate} has no public getter for its current {@code producerListener} (only {@code
 * setProducerListener}), so the existing listener is read via {@link DirectFieldAccessor} — the same
 * Spring-provided mechanism the framework itself uses for property access with no JavaBean accessor —
 * and composed with the capturing listener rather than replaced. Reading the field is best-effort: if
 * it fails for any reason, the capturing listener is still installed (nothing to compose with), so
 * sends are never silently left uncaptured, and the application's own listener is never dropped.</p>
 */
public final class KafkaProducerCaptureBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerCaptureBeanPostProcessor.class);

    private final ObjectProvider<KafkaActivityRecorder> recorderProvider;

    public KafkaProducerCaptureBeanPostProcessor(ObjectProvider<KafkaActivityRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof KafkaTemplate<?, ?> template)) {
            return bean;
        }
        KafkaActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            @SuppressWarnings("unchecked")
            ProducerListener<Object, Object> existing = (ProducerListener<Object, Object>)
                    new DirectFieldAccessor(template).getPropertyValue("producerListener");
            @SuppressWarnings("unchecked")
            KafkaTemplate<Object, Object> untyped = (KafkaTemplate<Object, Object>) template;
            untyped.setProducerListener(new CapturingProducerListener(existing, recorder));
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable Kafka producer capture for KafkaTemplate bean '{}'; leaving it "
                            + "unwrapped",
                    beanName,
                    ex);
        }
        return bean;
    }

    /**
     * Records every send outcome into the recorder, then delegates to the composed listener (the
     * application's own, or {@code null} when none was configured/readable).
     */
    private static final class CapturingProducerListener implements ProducerListener<Object, Object> {

        private final ProducerListener<Object, Object> delegate;
        private final KafkaActivityRecorder recorder;

        private CapturingProducerListener(ProducerListener<Object, Object> delegate, KafkaActivityRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public void onSuccess(ProducerRecord<Object, Object> producerRecord, RecordMetadata recordMetadata) {
            recorder.recordProduce(
                    producerRecord.topic(),
                    recordMetadata == null ? producerRecord.partition() : recordMetadata.partition(),
                    keyOf(producerRecord),
                    null, // ProducerListener carries no send-start timestamp, so duration is never known here
                    true,
                    null);
            if (delegate != null) {
                delegate.onSuccess(producerRecord, recordMetadata);
            }
        }

        @Override
        public void onError(
                ProducerRecord<Object, Object> producerRecord, RecordMetadata recordMetadata, Exception exception) {
            recorder.recordProduce(
                    producerRecord.topic(),
                    producerRecord.partition(),
                    keyOf(producerRecord),
                    null, // see onSuccess: no send-start timestamp is available to compute a duration
                    false,
                    exception == null ? null : exception.getMessage());
            if (delegate != null) {
                delegate.onError(producerRecord, recordMetadata, exception);
            }
        }

        private static String keyOf(ProducerRecord<Object, Object> producerRecord) {
            Object key = producerRecord.key();
            return key == null ? null : String.valueOf(key);
        }
    }
}
