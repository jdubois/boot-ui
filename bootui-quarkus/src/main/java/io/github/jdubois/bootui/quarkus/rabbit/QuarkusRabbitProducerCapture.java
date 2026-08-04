package io.github.jdubois.bootui.quarkus.rabbit;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.smallrye.reactive.messaging.OutgoingInterceptor;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Captures SmallRye Reactive Messaging RabbitMQ <em>sends</em> into the shared, framework-neutral
 * {@link RabbitActivityRecorder} — the Quarkus analogue of the Spring adapter's
 * {@code RabbitProducerCaptureBeanPostProcessor}, feeding the exact same recorder so both adapters render
 * identical {@code MESSAGING} entries in the Live Activity feed. Quarkus applications use SmallRye's
 * {@code @Outgoing} channel model (via {@code quarkus-messaging-rabbitmq}), not Spring's imperative
 * {@code RabbitTemplate} API, so the interception point is SmallRye's {@link OutgoingInterceptor} SPI: a
 * global (default-qualified) interceptor bean SmallRye auto-discovers and applies to every outgoing
 * channel that has no channel-specific interceptor of its own.
 *
 * <p><strong>This class (with {@link QuarkusRabbitConsumerCapture}) is the sole importer of
 * {@code io.smallrye.reactive.messaging.rabbitmq.*} types in the Quarkus adapter's capture path (R2).
 * </strong> The framework-neutral engine and the always-produced {@link RabbitActivityRecorder} never
 * import these; this bean is class-presence-gated (registered only when
 * {@code quarkus-messaging-rabbitmq} is on the classpath, and
 * {@linkplain io.quarkus.arc.deployment.ExcludedTypeBuildItem excluded} from bean discovery otherwise)
 * so Arc never links the messaging API in an app without that extension — mirroring the
 * Email-panel optional-dependency boundary.</p>
 *
 * <p><strong>Behavioral contract:</strong> metadata only (never the payload — only exchange/routingKey and
 * the correlationId when {@code bootui.rabbitmq.capture-correlation-id} is on, enforced by the recorder
 * itself); a no-op for non-RabbitMQ channels (a Kafka or in-memory outgoing message carries no
 * {@link OutgoingRabbitMQMetadata}, so nothing is recorded); pass-through / never disrupt the app
 * ({@link #getPriority()} returns {@link Integer#MAX_VALUE}, and SmallRye applies only the
 * lowest-priority interceptor per channel, so an application that registers its own outgoing interceptor
 * for a channel always wins and BootUI steps aside for it); and fail-open (any error while inspecting
 * metadata or recording is caught and logged at warn, never disrupting the send).</p>
 *
 * <p>Producer duration is always {@code null} (the ack callback carries no send-start timestamp).</p>
 */
@ApplicationScoped
public class QuarkusRabbitProducerCapture implements OutgoingInterceptor {

    private static final Logger log = Logger.getLogger(QuarkusRabbitProducerCapture.class);

    private final RabbitActivityRecorder recorder;

    @Inject
    public QuarkusRabbitProducerCapture(RabbitActivityRecorder recorder) {
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
            OutgoingRabbitMQMetadata metadata =
                    message.getMetadata(OutgoingRabbitMQMetadata.class).orElse(null);
            if (metadata == null) {
                // Not a RabbitMQ message (Kafka / in-memory channel): pass through and record nothing.
                return;
            }
            // Exchange is a channel-level config in SmallRye RabbitMQ, not per-message; record null here.
            recorder.recordPublish(
                    null,
                    metadata.getRoutingKey(),
                    null, // no send-start timestamp in the ack callback, so duration is always unknown
                    success,
                    errorMessage,
                    metadata.getCorrelationId());
        } catch (RuntimeException ex) {
            log.warn("BootUI could not capture an outgoing RabbitMQ message; leaving it untouched", ex);
        }
    }
}
