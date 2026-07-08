package io.github.jdubois.bootui.quarkus.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.smallrye.reactive.messaging.IncomingInterceptor;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Captures SmallRye Reactive Messaging Kafka <em>deliveries</em> into the shared, framework-neutral
 * {@link KafkaActivityRecorder} — the Quarkus analogue of the Spring adapter's
 * {@code KafkaConsumerCaptureBeanPostProcessor}, feeding the exact same recorder so both adapters render
 * identical {@code MESSAGING} entries in the Live Activity feed. Quarkus applications use SmallRye's
 * {@code @Incoming} channel model (via {@code quarkus-messaging-kafka}), not Spring's imperative
 * {@code @KafkaListener} API, so the interception point is SmallRye's {@link IncomingInterceptor} SPI: a
 * global (default-qualified) interceptor bean SmallRye auto-discovers and applies to every incoming
 * channel that has no channel-specific interceptor of its own.
 *
 * <p><strong>This class (with {@link QuarkusKafkaProducerCapture}) is the sole importer of
 * {@code io.smallrye.reactive.messaging.*} and Kafka-connector metadata types in the Quarkus adapter's
 * capture path (R2).</strong> It is capability-gated exactly like its producer twin (registered only
 * when {@code Capability.KAFKA} is present, {@linkplain io.quarkus.arc.deployment.ExcludedTypeBuildItem
 * excluded} otherwise), so Arc never links the messaging API in an app without
 * {@code quarkus-messaging-kafka}.</p>
 *
 * <p><strong>Behavioral contract, preserved from the Spring capture mechanism:</strong> metadata only
 * (never the payload — only topic/partition/offset and the key, when {@code bootui.kafka.capture-key} is
 * on, enforced by the recorder itself); a no-op for non-Kafka channels (an in-memory, RabbitMQ or JMS
 * delivery carries no {@link IncomingKafkaRecordMetadata}, so nothing is recorded — RabbitMQ and JMS are
 * out of scope); pass-through / never disrupt the app ({@link #getPriority()} returns
 * {@link Integer#MAX_VALUE}, and SmallRye applies only the lowest-priority interceptor per channel, so an
 * application that registers its own incoming interceptor for a channel always wins and BootUI steps
 * aside for it); and fail-open (any error while inspecting metadata or recording is caught and logged at
 * warn, never disrupting the delivery or its ack/nack).</p>
 *
 * <p>Consumer duration is timed from {@link #afterMessageReceive} to the terminal ack/nack via a
 * {@link CaptureStart} marker attached to the message: the same message instance {@code
 * afterMessageReceive} returns is the one SmallRye hands to {@code onMessageAck}/{@code onMessageNack},
 * so the marker travels with it and no shared per-message state is needed. The Kafka consumer group id is
 * not exposed on {@link IncomingKafkaRecordMetadata}, so {@code groupId} stays {@code null}; the channel
 * name is used as the listener id (the closest Quarkus analogue to a Spring listener id).</p>
 */
@ApplicationScoped
public class QuarkusKafkaConsumerCapture implements IncomingInterceptor {

    private static final Logger log = Logger.getLogger(QuarkusKafkaConsumerCapture.class);

    private final KafkaActivityRecorder recorder;

    @Inject
    public QuarkusKafkaConsumerCapture(KafkaActivityRecorder recorder) {
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

    /**
     * Stamps a {@link CaptureStart} marker on a Kafka message when capture is enabled, so the terminal
     * ack/nack can time the delivery. Returns the message unchanged for non-Kafka channels (or when
     * capture is disabled), and never throws.
     */
    @Override
    public Message<?> afterMessageReceive(Message<?> message) {
        if (!recorder.isEnabled()) {
            return message;
        }
        try {
            if (message.getMetadata(IncomingKafkaRecordMetadata.class).isEmpty()) {
                return message;
            }
            return message.addMetadata(new CaptureStart(System.nanoTime()));
        } catch (RuntimeException ex) {
            log.warn("BootUI could not begin timing an incoming Kafka message; leaving it untouched", ex);
            return message;
        }
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
            IncomingKafkaRecordMetadata<?, ?> metadata =
                    message.getMetadata(IncomingKafkaRecordMetadata.class).orElse(null);
            if (metadata == null) {
                return;
            }
            Long durationMillis = message.getMetadata(CaptureStart.class)
                    .map(start -> Math.max(0L, (System.nanoTime() - start.nanos()) / 1_000_000L))
                    .orElse(null);
            recorder.recordConsume(
                    metadata.getTopic(),
                    metadata.getPartition(),
                    metadata.getOffset(),
                    keyOf(metadata.getKey()),
                    durationMillis,
                    success,
                    errorMessage,
                    null, // IncomingKafkaRecordMetadata exposes no consumer group id, so leave it null
                    metadata.getChannel());
        } catch (RuntimeException ex) {
            log.warn("BootUI could not capture an incoming Kafka message; leaving it untouched", ex);
        }
    }

    private static String keyOf(Object key) {
        return key == null ? null : String.valueOf(key);
    }

    /**
     * A monotonic-clock marker attached to an incoming Kafka message at reception, read back at ack/nack
     * to time the delivery. Carried as message metadata so it travels with the (single) message instance
     * and needs no shared per-message state.
     */
    record CaptureStart(long nanos) {}
}
