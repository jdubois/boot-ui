package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
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
 *
 * <p>Recording is fail-open: any failure while reading the record (including a key whose {@code
 * toString()} throws) or while recording it is caught and logged at warn, and the composed delegate is
 * still invoked afterward — mirroring {@code QuarkusKafkaProducerCapture}'s equivalent guarantee.</p>
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
            ProducerInterceptor<Object, Object> existingInterceptor = (ProducerInterceptor<Object, Object>)
                    new DirectFieldAccessor(template).getPropertyValue("producerInterceptor");
            @SuppressWarnings("unchecked")
            KafkaTemplate<Object, Object> untyped = (KafkaTemplate<Object, Object>) template;
            SenderCorrelations senders = new SenderCorrelations();
            untyped.setProducerInterceptor(new SenderSnapshotInterceptor(existingInterceptor, recorder, senders));
            untyped.setProducerListener(new CapturingProducerListener(existing, recorder, senders));
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
        private final SenderCorrelations senders;

        private CapturingProducerListener(
                ProducerListener<Object, Object> delegate, KafkaActivityRecorder recorder, SenderCorrelations senders) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.senders = senders;
        }

        @Override
        public void onSuccess(ProducerRecord<Object, Object> producerRecord, RecordMetadata recordMetadata) {
            try {
                // Boxing recordMetadata.partition() explicitly keeps both ternary branches Integer;
                // mixing Integer with a raw int branch here would make the conditional expression's own
                // type int (JLS 15.25 binary numeric promotion), unboxing producerRecord.partition() even
                // when this branch isn't selected at runtime, and NPEing whenever it is null.
                Integer partition = recordMetadata == null
                        ? producerRecord.partition()
                        : Integer.valueOf(recordMetadata.partition());
                recorder.recordProduce(
                        producerRecord.topic(),
                        partition,
                        keyOf(producerRecord),
                        null, // ProducerListener carries no send-start timestamp, so duration is never known here
                        true,
                        null,
                        senders.take(producerRecord));
            } catch (RuntimeException ex) {
                log.warn("BootUI could not capture an outgoing Kafka message; leaving it untouched", ex);
            }
            if (delegate != null) {
                delegate.onSuccess(producerRecord, recordMetadata);
            }
        }

        @Override
        public void onError(
                ProducerRecord<Object, Object> producerRecord, RecordMetadata recordMetadata, Exception exception) {
            try {
                recorder.recordProduce(
                        producerRecord.topic(),
                        producerRecord.partition(),
                        keyOf(producerRecord),
                        null, // see onSuccess: no send-start timestamp is available to compute a duration
                        false,
                        exception == null ? null : exception.getMessage(),
                        senders.take(producerRecord));
            } catch (RuntimeException ex) {
                log.warn("BootUI could not capture an outgoing Kafka message; leaving it untouched", ex);
            }
            if (delegate != null) {
                delegate.onError(producerRecord, recordMetadata, exception);
            }
        }

        private static String keyOf(ProducerRecord<Object, Object> producerRecord) {
            Object key = producerRecord.key();
            return key == null ? null : String.valueOf(key);
        }
    }

    /**
     * Snapshots the sender's BootUI correlation on the sending thread ({@code docs/PLAN-v2.md} §5.1), because Kafka
     * reports the outcome to {@link CapturingProducerListener} on its own I/O thread. {@code KafkaTemplate} passes the
     * record this returns to that callback, so the snapshot is keyed by the record's identity. Delegates to the
     * application's own interceptor first, and never changes the record.
     */
    private static final class SenderSnapshotInterceptor implements ProducerInterceptor<Object, Object> {

        private final ProducerInterceptor<Object, Object> delegate;
        private final KafkaActivityRecorder recorder;
        private final SenderCorrelations senders;

        private SenderSnapshotInterceptor(
                ProducerInterceptor<Object, Object> delegate,
                KafkaActivityRecorder recorder,
                SenderCorrelations senders) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.senders = senders;
        }

        @Override
        public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            ProducerRecord<Object, Object> sent = delegate == null ? record : delegate.onSend(record);
            try {
                senders.put(sent, recorder.currentCorrelation());
            } catch (RuntimeException ex) {
                // A missing snapshot only leaves the message top-level; the send itself is never disturbed.
            }
            return sent;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
            if (delegate != null) {
                delegate.onAcknowledgement(metadata, exception);
            }
        }

        @Override
        public void close() {
            if (delegate != null) {
                delegate.close();
            }
        }

        @Override
        public void configure(Map<String, ?> configs) {
            if (delegate != null) {
                delegate.configure(configs);
            }
        }
    }

    /**
     * Sender correlations of in-flight records, by record identity ({@link ProducerRecord} overrides {@code equals}).
     * Each entry is taken when its outcome is reported. Bounded, so records whose outcome never arrives cannot grow
     * it: past the bound it is cleared, and those messages simply stay top-level.
     */
    static final class SenderCorrelations {

        static final int MAX_IN_FLIGHT = 10_000;

        private final Map<ProducerRecord<?, ?>, CorrelationContext> inFlight =
                Collections.synchronizedMap(new IdentityHashMap<>());

        void put(ProducerRecord<?, ?> record, CorrelationContext sender) {
            if (record == null || sender == null || sender.isEmpty()) {
                return;
            }
            synchronized (inFlight) {
                if (inFlight.size() >= MAX_IN_FLIGHT) {
                    inFlight.clear();
                }
                inFlight.put(record, sender);
            }
        }

        CorrelationContext take(ProducerRecord<?, ?> record) {
            CorrelationContext sender = record == null ? null : inFlight.remove(record);
            return sender == null ? CorrelationContext.NONE : sender;
        }
    }
}
