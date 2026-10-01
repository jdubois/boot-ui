package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario against Spring MVC with tracing, in the
 * Docker-free {@code dev} profile, with Kafka sending to an in-JVM broker. Buffers are raised so that no scenario
 * request or child is evicted.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=" + AbstractSpringCorrelationScenario.DEV_EXCLUSIONS_WITHOUT_KAFKA,
            "spring.kafka.consumer.group-id=correlation-scenario",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            AbstractSpringCorrelationScenario.EMBEDDED_KAFKA_BROKERS,
            "spring.datasource.url=jdbc:h2:mem:bootui_correlation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/correlation-coverage/application-bootui.properties",
            "bootui.http-exchanges.max-exchanges=1000",
            "bootui.activity.max-entries=2000"
        })
@EmbeddedKafka(
        partitions = 1,
        topics = AbstractSpringCorrelationScenario.ORDERS_TOPIC,
        bootstrapServersProperty = AbstractSpringCorrelationScenario.EMBEDDED_KAFKA_BROKERS_PROPERTY)
@Import(CorrelationScenarioRoutes.class)
class SpringCorrelationCoverageTest extends AbstractSpringCorrelationScenario {

    @Override
    protected String runtimeLabel() {
        return "spring-mvc";
    }

    @Override
    protected Tracing tracing() {
        return Tracing.ON;
    }
}
