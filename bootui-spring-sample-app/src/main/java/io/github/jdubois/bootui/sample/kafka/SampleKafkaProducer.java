package io.github.jdubois.bootui.sample.kafka;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes sample "order created" events to Kafka so the BootUI Kafka panel (and Live Activity's
 * Kafka signal) have producer data to display.
 *
 * <p>Kafka auto-configuration is excluded in the Docker-free "dev" profile, so no
 * {@link KafkaTemplate} bean exists there. The template is injected through an
 * {@link ObjectProvider} and every send is null-checked, mirroring {@code ChatController}'s
 * graceful degradation for the (also Docker-only) Spring AI {@code ChatClient}.
 *
 * <p>One event is seeded once at startup, and {@link #sendSampleOrderEvent()} lets callers (e.g.
 * the sample "Send Kafka message" endpoint) publish one more on demand.
 */
@Component
public class SampleKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(SampleKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicInteger orderSequence = new AtomicInteger(1000);

    public SampleKafkaProducer(ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    }

    /** Seeds a single sample order event once the application is ready, so the panel is not empty. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedSampleOrderEvent() {
        if (kafkaTemplate != null) {
            sendSampleOrderEvent();
        }
    }

    /**
     * Publishes one more sample "order created" event on demand and returns a short human-readable
     * summary, or a clear "not configured" message when Kafka is unavailable (e.g. the "dev"
     * profile).
     */
    public String sendSampleOrderEvent() {
        if (kafkaTemplate == null) {
            return "Kafka is not configured. Activate the \"docker\" profile to start a broker "
                    + "(-Dspring-boot.run.profiles=docker).";
        }
        int orderId = orderSequence.incrementAndGet();
        String key = "order-" + orderId;
        String value = "{\"orderId\":%d,\"status\":\"CREATED\",\"createdAt\":\"%s\"}".formatted(orderId, Instant.now());
        try {
            kafkaTemplate.send(SampleKafkaConfiguration.ORDERS_TOPIC, key, value);
            return "Published order #" + orderId + " to \"" + SampleKafkaConfiguration.ORDERS_TOPIC + "\"";
        } catch (Exception ex) {
            log.warn("Sample app could not publish the demo Kafka event", ex);
            return "Could not publish the demo Kafka event: " + ex.getMessage();
        }
    }
}
