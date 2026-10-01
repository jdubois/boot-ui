package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.AbstractCorrelationCoverageTest;
import io.github.jdubois.bootui.sample.kafka.SampleKafkaConfiguration;
import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The routes, floors, and threads the {@code docs/PLAN-v2.md} §5.1 correlation scenario uses on Spring MVC, shared by
 * the runner with tracing and the runner without it. The routes cover SQL, an HTTP Basic secured request with SQL, a
 * cached read, a request-thread exception, a Kafka send to an in-JVM broker, and a query handed to a raw executor.
 */
abstract class AbstractSpringCorrelationScenario extends AbstractCorrelationCoverageTest {

    /**
     * The {@code dev} profile's auto-configuration exclusions without Kafka's, so the Kafka route sends to the
     * embedded broker. The properties a runner sets replace the profile's list.
     */
    static final String DEV_EXCLUSIONS_WITHOUT_KAFKA =
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,"
                    + "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration,"
                    + "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration";

    static final String ORDERS_TOPIC = SampleKafkaConfiguration.ORDERS_TOPIC;

    /**
     * The system property the embedded broker publishes its address under. spring-kafka-test sets it for the rest of
     * the JVM and defaults it to {@code spring.kafka.bootstrap-servers}, which would leak into every later test, so the
     * scenario names its own.
     */
    static final String EMBEDDED_KAFKA_BROKERS_PROPERTY = "bootui.correlation-scenario.kafka-brokers";

    /** Points Kafka at the embedded broker. */
    static final String EMBEDDED_KAFKA_BROKERS =
            "spring.kafka.bootstrap-servers=${" + EMBEDDED_KAFKA_BROKERS_PROPERTY + "}";

    @LocalServerPort
    int port;

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected List<Traffic> traffic() {
        return List.of(
                Traffic.anonymous("/api/sample/product-search?term=console", 8),
                Traffic.basicAuth("/api/secure/products", "admin", "admin", 4),
                Traffic.anonymous("/api/sample/products", 4),
                Traffic.anonymous("/api/sample/boom", 4),
                Traffic.anonymous("/api/sample/send-kafka-message", 4),
                Traffic.anonymous("/scenario/raw-executor-query", 2));
    }

    /**
     * Request-thread SQL, security events, cache accesses, and exceptions carry their request id (M1-3, M1-5), and a
     * Kafka send carries its sender's, taken on the request thread before the broker acknowledges it on its own I/O
     * thread (M1-6b2), in every phase.
     */
    @Override
    protected Map<String, Double> minimumNestedShares(Phase phase) {
        return Map.of("SQL", 1.0, "SECURITY", 1.0, "CACHE", 1.0, "EXCEPTION", 1.0, "MESSAGING", 1.0);
    }

    @Override
    protected Pattern requestThreadPattern() {
        // The sample enables virtual threads, so Tomcat serves requests on tomcat-handler-N; http-nio-…-exec-N
        // covers the platform-thread executor.
        return Pattern.compile("tomcat-handler-\\d+|http-nio-.+-exec-\\d+");
    }

    @Override
    protected Pattern unownedThreadPattern() {
        return Pattern.compile(CorrelationScenarioRoutes.RAW_EXECUTOR_THREADS);
    }
}
