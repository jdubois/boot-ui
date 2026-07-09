package io.github.jdubois.bootui.sample.kafka;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers one more sample "order created" Kafka event on demand so the BootUI Kafka panel can be
 * exercised interactively (and by the Playwright e2e suite). Only produces data when the "docker"
 * profile is active (see {@link SampleKafkaProducer}).
 */
@RestController
@RequestMapping("/api/sample")
public class SampleKafkaController {

    private final SampleKafkaProducer kafkaProducer;

    public SampleKafkaController(SampleKafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    @GetMapping("/send-kafka-message")
    public String sendKafkaMessage() {
        return kafkaProducer.sendSampleOrderEvent();
    }
}
