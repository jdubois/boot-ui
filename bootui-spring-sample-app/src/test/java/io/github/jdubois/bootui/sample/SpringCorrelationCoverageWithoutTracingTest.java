package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario against Spring MVC with tracing off, so that the
 * floors prove BootUI's own request id carries correlation. {@code management.tracing.enabled=false} does not stop
 * Spring Boot 4 from creating spans in a test, so the OpenTelemetry tracer's auto-configuration is excluded, which
 * leaves Micrometer's no-op tracer.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=" + AbstractSpringCorrelationScenario.DEV_EXCLUSIONS_WITHOUT_KAFKA
                    + ",org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure"
                    + ".OpenTelemetryTracingAutoConfiguration",
            "spring.kafka.consumer.group-id=correlation-scenario-without-tracing",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            AbstractSpringCorrelationScenario.EMBEDDED_KAFKA_BROKERS,
            "spring.datasource.url=jdbc:h2:mem:bootui_correlation_untraced;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/correlation-coverage/application-bootui-untraced.properties",
            "bootui.http-exchanges.max-exchanges=1000",
            "bootui.activity.max-entries=2000"
        })
@EmbeddedKafka(
        partitions = 1,
        topics = AbstractSpringCorrelationScenario.ORDERS_TOPIC,
        bootstrapServersProperty = AbstractSpringCorrelationScenario.EMBEDDED_KAFKA_BROKERS_PROPERTY)
@Import(CorrelationScenarioRoutes.class)
class SpringCorrelationCoverageWithoutTracingTest extends AbstractSpringCorrelationScenario {

    @Override
    protected String runtimeLabel() {
        return "spring-mvc-without-tracing";
    }

    @Override
    protected Tracing tracing() {
        return Tracing.OFF;
    }
}
