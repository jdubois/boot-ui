package io.github.jdubois.bootui.sample.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link SampleKafkaConfiguration#ORDERS_TOPIC} so the BootUI Kafka panel (and Live
 * Activity's Kafka signal) have matching consumer data alongside {@link SampleKafkaProducer}'s
 * producer records.
 *
 * <p>No defensive coding is needed here for the Docker-free "dev" profile: excluding
 * {@code KafkaAutoConfiguration} also excludes {@code @EnableKafka} processing, so this listener
 * simply becomes inert (never registered) rather than failing to start.
 */
@Component
public class SampleOrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(SampleOrderEventListener.class);

    @KafkaListener(topics = SampleKafkaConfiguration.ORDERS_TOPIC)
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        log.info(
                "Consumed order event from {}-{}@{}: key={} value={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value());
    }
}
