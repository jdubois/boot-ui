package io.github.jdubois.bootui.sample.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the sample "orders.created" topic used by {@link SampleKafkaProducer} and
 * {@link SampleOrderEventListener}.
 *
 * <p>A {@link NewTopic} bean is safe to declare unconditionally: it is a plain descriptor with no
 * dependencies, so in the Docker-free "dev" profile (where {@code KafkaAutoConfiguration} — and
 * therefore the {@code KafkaAdmin} that would apply this descriptor — is excluded) it simply sits
 * unused in the context.
 */
@Configuration
public class SampleKafkaConfiguration {

    /** Topic shared by the sample producer, listener, and controller. */
    public static final String ORDERS_TOPIC = "orders.created";

    @Bean
    public NewTopic ordersCreatedTopic() {
        return TopicBuilder.name(ORDERS_TOPIC).partitions(1).replicas(1).build();
    }
}
