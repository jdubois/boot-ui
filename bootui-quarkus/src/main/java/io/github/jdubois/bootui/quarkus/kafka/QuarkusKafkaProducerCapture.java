package io.github.jdubois.bootui.quarkus.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.smallrye.reactive.messaging.OutgoingInterceptor;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Captures SmallRye Reactive Messaging Kafka <em>sends</em> into the shared, framework-neutral
 * {@link KafkaActivityRecorder} — the Quarkus analogue of the Spring adapter's
 * {@code KafkaProducerCaptureBeanPostProcessor}, feeding the exact same recorder so both adapters render
 * identical {@code MESSAGING} entries in the Live Activity feed. Quarkus applications use SmallRye's
 * {@code @Outgoing} channel model (via {@code quarkus-messaging-kafka}), not Spring's imperative
 * {@code KafkaTemplate} API, so the interception point is SmallRye's {@link OutgoingInterceptor} SPI: a
 * global (default-qualified) interceptor bean SmallRye auto-discovers and applies to every outgoing
 * channel that has no channel-specific interceptor of its own.
 *
 * <p><strong>This class (with {@link QuarkusKafkaConsumerCapture}) is the sole importer of
 * {@code io.smallrye.reactive.messaging.*} and Kafka-connector metadata types in the Quarkus adapter's
 * capture path (R2).</strong> The framework-neutral engine and the always-produced
 * {@link KafkaActivityRecorder} never import these; this bean is capability-gated (registered only when
 * {@code Capability.KAFKA} is present, and excluded via {@code
 * io.quarkus.arc.deployment.ExcludedTypeBuildItem} from bean discovery otherwise) so Arc never links the
 * messaging API in an app without
 * {@code quarkus-messaging-kafka} — mirroring the Cache/Flyway/Liquibase optional-dependency boundary.</p>
 *
 * <p><strong>Behavioral contract, preserved from the Spring capture mechanism:</strong> metadata only
 * (never the payload — only topic/partition and the key, when {@code bootui.kafka.capture-key} is on,
 * enforced by the recorder itself); a no-op for non-Kafka channels (an in-memory, RabbitMQ or JMS
 * outgoing message carries no {@link OutgoingKafkaRecordMetadata}, so nothing is recorded — RabbitMQ and
 * JMS are out of scope); pass-through / never disrupt the app ({@link #getPriority()} returns
 * {@link Integer#MAX_VALUE}, and SmallRye applies only the lowest-priority interceptor per channel, so an
 * application that registers its own outgoing interceptor for a channel always wins and BootUI steps
 * aside for it); and fail-open (any error while inspecting metadata or recording is caught and logged at
 * warn, never disrupting the send).</p>
 *
 * <p>Producer duration is always {@code null} (the ack callback carries no send-start timestamp, exactly
 * like Spring's {@code ProducerListener}).</p>
 */
@ApplicationScoped
public class QuarkusKafkaProducerCapture implements OutgoingInterceptor {

    private static final Logger log = Logger.getLogger(QuarkusKafkaProducerCapture.class);

    private final KafkaActivityRecorder recorder;

    @Inject
    public QuarkusKafkaProducerCapture(KafkaActivityRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Highest priority value so SmallRye's ascending-priority, first-wins interceptor selection always
     * prefers an application-defined interceptor over this one — BootUI never displaces the app's own.
     */
    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void onMessageAck(Message<?> message) {
        record(message, true, null);
    }

    @Override
    public void onMessageNack(Message<?> message, Throwable failure) {
        record(message, false, failure == null ? null : failure.getMessage());
    }

    private void record(Message<?> message, boolean success, String errorMessage) {
        if (!recorder.isEnabled()) {
            return;
        }
        try {
            OutgoingKafkaRecordMetadata<?> metadata =
                    message.getMetadata(OutgoingKafkaRecordMetadata.class).orElse(null);
            if (metadata == null) {
                // Not a Kafka message (in-memory / RabbitMQ / JMS channel, or a plain send with no Kafka
                // routing metadata): pass through and record nothing.
                return;
            }
            recorder.recordProduce(
                    metadata.getTopic(),
                    normalizePartition(metadata.getPartition()),
                    keyOf(metadata.getKey()),
                    null, // the ack callback carries no send-start timestamp, so duration is never known here
                    success,
                    errorMessage);
        } catch (RuntimeException ex) {
            log.warn("BootUI could not capture an outgoing Kafka message; leaving it untouched", ex);
        }
    }

    private static String keyOf(Object key) {
        return key == null ? null : String.valueOf(key);
    }

    /** An unassigned outgoing partition is {@code -1}; surface that as {@code null} (no partition). */
    private static Integer normalizePartition(int partition) {
        return partition < 0 ? null : partition;
    }
}
