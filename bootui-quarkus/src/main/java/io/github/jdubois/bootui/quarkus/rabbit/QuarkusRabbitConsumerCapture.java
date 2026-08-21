package io.github.jdubois.bootui.quarkus.rabbit;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.smallrye.reactive.messaging.IncomingInterceptor;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Captures SmallRye Reactive Messaging RabbitMQ <em>deliveries</em> into the shared, framework-neutral
 * {@link RabbitActivityRecorder} — the Quarkus analogue of the Spring adapter's
 * {@code RabbitConsumerCaptureBeanPostProcessor}, feeding the exact same recorder so both adapters render
 * identical {@code MESSAGING} entries in the Live Activity feed. Quarkus applications use SmallRye's
 * {@code @Incoming} channel model (via {@code quarkus-messaging-rabbitmq}), not Spring's
 * {@code @RabbitListener} API, so the interception point is SmallRye's {@link IncomingInterceptor} SPI: a
 * global (default-qualified) interceptor bean SmallRye auto-discovers and applies to every incoming
 * channel that has no channel-specific interceptor of its own.
 *
 * <p><strong>This class (with {@link QuarkusRabbitProducerCapture}) is the sole importer of
 * {@code io.smallrye.reactive.messaging.rabbitmq.*} types in the Quarkus adapter's capture path (R2).
 * </strong> It is class-presence-gated exactly like its producer twin (registered only when
 * {@code quarkus-messaging-rabbitmq} is on the classpath, excluded with {@code ExcludedTypeBuildItem}
 * otherwise), so Arc never links the messaging API in an app without that extension.</p>
 *
 * <p><strong>Behavioral contract:</strong> metadata only (never the payload — only exchange/routingKey and
 * the correlationId when {@code bootui.rabbitmq.capture-correlation-id} is on, enforced by the recorder
 * itself); a no-op for non-RabbitMQ channels (a Kafka or in-memory incoming message carries no
 * {@link IncomingRabbitMQMetadata}); pass-through / never disrupt the app ({@link #getPriority()}
 * returns {@link Integer#MAX_VALUE}, and SmallRye applies only the lowest-priority interceptor per channel,
 * so an application that registers its own incoming interceptor for a channel always wins and BootUI steps
 * aside for it); and fail-open (any error while inspecting metadata or recording is caught and logged at
 * warn, never disrupting the delivery or its ack/nack).</p>
 *
 * <p>Consumer duration is timed from {@link #afterMessageReceive} to the terminal ack/nack via a
 * {@link CaptureStart} marker attached to the message — the same pattern {@code
 * QuarkusKafkaConsumerCapture} uses.</p>
 */
@ApplicationScoped
public class QuarkusRabbitConsumerCapture implements IncomingInterceptor {

    private static final Logger log = Logger.getLogger(QuarkusRabbitConsumerCapture.class);

    private final RabbitActivityRecorder recorder;

    @Inject
    public QuarkusRabbitConsumerCapture(RabbitActivityRecorder recorder) {
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
     * Stamps a {@link CaptureStart} marker on a RabbitMQ message when capture is enabled, so the terminal
     * ack/nack can time the delivery. Returns the message unchanged for non-RabbitMQ channels (or when
     * capture is disabled), and never throws.
     */
    @Override
    public Message<?> afterMessageReceive(Message<?> message) {
        if (!recorder.isEnabled()) {
            return message;
        }
        try {
            if (message.getMetadata(IncomingRabbitMQMetadata.class).isEmpty()) {
                return message;
            }
            return message.addMetadata(new CaptureStart(System.nanoTime()));
        } catch (RuntimeException ex) {
            log.warn("BootUI could not begin timing an incoming RabbitMQ message; leaving it untouched", ex);
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
            IncomingRabbitMQMetadata metadata =
                    message.getMetadata(IncomingRabbitMQMetadata.class).orElse(null);
            if (metadata == null) {
                return;
            }
            Long durationMillis = message.getMetadata(CaptureStart.class)
                    .map(start -> Math.max(0L, (System.nanoTime() - start.nanos()) / 1_000_000L))
                    .orElse(null);
            recorder.recordConsume(
                    metadata.getExchange(),
                    metadata.getRoutingKey(),
                    null, // queue name is not exposed on IncomingRabbitMQMetadata; leave it null
                    durationMillis,
                    success,
                    errorMessage,
                    metadata.getCorrelationId().orElse(null));
        } catch (RuntimeException ex) {
            log.warn("BootUI could not capture an incoming RabbitMQ message; leaving it untouched", ex);
        }
    }

    /**
     * A monotonic-clock marker attached to an incoming RabbitMQ message at reception, read back at ack/nack
     * to time the delivery. Carried as message metadata so it travels with the (single) message instance
     * and needs no shared per-message state.
     */
    record CaptureStart(long nanos) {}
}
