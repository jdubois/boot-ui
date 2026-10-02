package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
                Sent sent = senders.take(producerRecord);
                recorder.recordProduceNanos(
                        producerRecord.topic(),
                        partition,
                        keyOf(producerRecord),
                        sent.durationNanos(),
                        true,
                        null,
                        sent.sender());
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
                Sent sent = senders.take(producerRecord);
                recorder.recordProduceNanos(
                        producerRecord.topic(),
                        producerRecord.partition(),
                        keyOf(producerRecord),
                        sent.durationNanos(),
                        false,
                        exception == null ? null : exception.getMessage(),
                        sent.sender());
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
     * The sender's correlation and the {@link System#nanoTime()} of each in-flight send, by record identity
     * ({@link ProducerRecord} overrides {@code equals}), so its event starts at the send and is timed to its outcome. A
     * record sent again before its first outcome is queued behind it, and each outcome takes the oldest send of its
     * record. Bounded, so sends whose outcome never arrives cannot grow it: past the bound every send of the record
     * first sent longest ago is forgotten at once, so a reused record's later outcomes stay top-level and untimed
     * rather than taking another send's snapshot.
     */
    static final class SenderCorrelations {

        static final int MAX_IN_FLIGHT = 10_000;

        private final Map<RecordKey, ArrayDeque<Sent>> inFlight = new LinkedHashMap<>();
        private int size;

        void put(ProducerRecord<?, ?> record, CorrelationContext sender) {
            if (record == null) {
                return;
            }
            Sent sent = new Sent(sender == null ? CorrelationContext.NONE : sender, System.nanoTime());
            synchronized (inFlight) {
                inFlight.computeIfAbsent(new RecordKey(record), key -> new ArrayDeque<>(1))
                        .addLast(sent);
                size++;
                Iterator<ArrayDeque<Sent>> oldest = inFlight.values().iterator();
                while (size > MAX_IN_FLIGHT && oldest.hasNext()) {
                    size -= oldest.next().size();
                    oldest.remove();
                }
            }
        }

        /** The oldest send of {@code record}, or an untimed one with no sender when it was not snapshotted. */
        Sent take(ProducerRecord<?, ?> record) {
            if (record == null) {
                return Sent.UNKNOWN;
            }
            synchronized (inFlight) {
                RecordKey key = new RecordKey(record);
                ArrayDeque<Sent> sends = inFlight.get(key);
                if (sends == null) {
                    return Sent.UNKNOWN;
                }
                Sent sent = sends.removeFirst();
                size--;
                if (sends.isEmpty()) {
                    inFlight.remove(key);
                }
                return sent;
            }
        }

        /** The sends in flight now, for tests. */
        int size() {
            synchronized (inFlight) {
                return size;
            }
        }
    }

    /** A record compared by identity, since equal records sent separately are separate sends. */
    private record RecordKey(ProducerRecord<?, ?> record) {

        @Override
        public boolean equals(Object other) {
            return other instanceof RecordKey key && key.record == record;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(record);
        }
    }

    /**
     * A record's send: who sent it and when, as a {@link System#nanoTime()}; {@link #UNKNOWN} when it was not
     * snapshotted, since any {@code long}, negative ones included, is a valid reading.
     */
    record Sent(CorrelationContext sender, long sentNanos, boolean timed) {

        static final Sent UNKNOWN = new Sent(CorrelationContext.NONE, 0, false);

        Sent(CorrelationContext sender, long sentNanos) {
            this(sender, sentNanos, true);
        }

        /** The nanoseconds from the send to now, or {@code null} when the send time is unknown. */
        Long durationNanos() {
            return timed ? Math.max(0, System.nanoTime() - sentNanos) : null;
        }
    }
}
