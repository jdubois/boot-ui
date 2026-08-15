package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ServiceMapNodeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the Quarkus binding for {@code GET /bootui/api/activity/service-map}: which beans it reads, that a
 * panel that is unavailable or disabled on this runtime contributes nothing, and that the resulting report
 * matches the shared contract the Spring adapters serve. Interpretation is the engine assembler's contract
 * and is covered by {@code ServiceMapAssemblerTests}.
 */
class LiveServiceMapResourceTests {

    @Test
    void mapsOutboundHttpAndMessagingEvidenceWhenTheirPanelsAreAvailable() {
        RestClientTraceRecorder rest = restRecorder();
        rest.record(
                "GET",
                "https://api.example.com/orders/42?token=opaque",
                "api.example.com",
                "/orders/42",
                200,
                12,
                true,
                null,
                "QuarkusRestClient",
                Map.of(),
                "main");
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);
        RabbitActivityRecorder rabbit = new RabbitActivityRecorder(true, false, 10, 32);
        rabbit.recordPublish("billing", "charged", 3L, true, null, null);

        ServiceMapReport report = resource(
                        available(
                                QuarkusPanelAvailability.REST_CLIENT_TRACE_PRESENT_KEY,
                                QuarkusPanelAvailability.KAFKA_PRESENT_KEY,
                                QuarkusPanelAvailability.RABBIT_PRESENT_KEY),
                        new HttpExchangeBuffer(10),
                        rest,
                        kafka,
                        rabbit,
                        null)
                .serviceMap();

        assertThat(report.available()).isTrue();
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::label)
                .contains("https://api.example.com", "orders", "billing → charged");
        assertThat(report.toString()).doesNotContain("token").doesNotContain("/orders/42");
    }

    @Test
    void mapsConfiguredConnectionPoolsWithTheirMaskedTarget() {
        ServiceMapReport report = resource(
                        available(QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY),
                        new HttpExchangeBuffer(10),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        pools("jdbc:postgresql://localhost:5432/shop"))
                .serviceMap();

        ServiceMapNodeDto pool = report.nodes().stream()
                .filter(node -> "JDBC".equals(node.protocol()))
                .findFirst()
                .orElseThrow();
        assertThat(pool.configured()).isTrue();
        assertThat(pool.observed()).isFalse();
        assertThat(pool.label()).isEqualTo("jdbc:postgresql://localhost:5432/shop");
        assertThat(report.sources()).contains("Connection Pools");
    }

    @Test
    void foldsCompletedIncomingRequestsIntoTheInboundLane() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        buffer.record(captured("GET", "http://localhost:8080/orders", 200));
        buffer.record(captured("POST", "http://localhost:8080/orders", 503));

        ServiceMapReport report = resource(
                        config(Map.of()),
                        buffer,
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null)
                .serviceMap();

        ServiceMapNodeDto inbound = report.nodes().stream()
                .filter(node -> "INBOUND".equals(node.kind()))
                .findFirst()
                .orElseThrow();
        assertThat(inbound.interactions()).isEqualTo(2);
        assertThat(inbound.failures()).isEqualTo(1);
    }

    @Test
    void contributesNothingFromAPanelThatIsDisabledByTheLiveAccessPolicy() {
        RestClientTraceRecorder rest = restRecorder();
        rest.record(
                "GET",
                "https://api.example.com/x",
                "api.example.com",
                "/x",
                200,
                12,
                true,
                null,
                "QuarkusRestClient",
                Map.of(),
                "main");
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);

        Map<String, String> properties = new HashMap<>();
        properties.put(QuarkusPanelAvailability.REST_CLIENT_TRACE_PRESENT_KEY, "true");
        properties.put(QuarkusPanelAvailability.KAFKA_PRESENT_KEY, "true");
        properties.put("bootui.panels.rest-client-trace.enabled", "false");
        properties.put("bootui.panels.kafka.enabled", "false");
        properties.put("bootui.panels.http-exchanges.enabled", "false");

        ServiceMapReport report = resource(
                        config(properties),
                        new HttpExchangeBuffer(10),
                        rest,
                        kafka,
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null)
                .serviceMap();

        assertThat(report.nodes()).isEmpty();
        assertThat(report.toString()).doesNotContain("api.example.com").doesNotContain("orders");
    }

    @Test
    void contributesNothingFromAPanelThatIsNotAvailableOnThisRuntime() {
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);

        // No KAFKA_PRESENT_KEY: the extension is absent, so the panel is unavailable even though the
        // always-produced recorder happens to hold evidence from a test.
        ServiceMapReport report = resource(
                        config(Map.of()),
                        new HttpExchangeBuffer(10),
                        restRecorder(),
                        kafka,
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null)
                .serviceMap();

        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("KAFKA");
    }

    @Test
    void survivesAnUnsatisfiedSqlTraceRecorderWithoutFailing() {
        ServiceMapReport report = resource(
                        available(QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY),
                        new HttpExchangeBuffer(10),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        pools("jdbc:postgresql://localhost:5432/shop"))
                .serviceMap();

        assertThat(report.available()).isTrue();
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("summarized"));
    }

    @Test
    void honestlyReportsCacheAsUnavailableSinceThisRuntimeHasNoCaptureSeam() {
        // Quarkus has no CacheActivityRecorder-equivalent bean at all (see this resource's own javadoc),
        // so the service map must never draw a CACHE dependency here no matter what else is available.
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        buffer.record(captured("GET", "http://localhost:8080/orders", 200));

        ServiceMapReport report = resource(
                        available(
                                QuarkusPanelAvailability.REST_CLIENT_TRACE_PRESENT_KEY,
                                QuarkusPanelAvailability.KAFKA_PRESENT_KEY,
                                QuarkusPanelAvailability.RABBIT_PRESENT_KEY,
                                QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY),
                        buffer,
                        restRecorder(),
                        new KafkaActivityRecorder(true, true, 10, 16),
                        new RabbitActivityRecorder(true, false, 10, 32),
                        pools("jdbc:postgresql://localhost:5432/shop"))
                .serviceMap();

        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
        assertThat(report.sources()).doesNotContain("Cache");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private LiveServiceMapResource resource(
            SmallRyeConfig config,
            HttpExchangeBuffer buffer,
            RestClientTraceRecorder rest,
            KafkaActivityRecorder kafka,
            RabbitActivityRecorder rabbit,
            ConnectionPoolService pools) {
        QuarkusExposurePolicy exposure = new QuarkusExposurePolicy(config);
        return new LiveServiceMapResource(
                buffer,
                exposure,
                new QuarkusPanelAvailability(config),
                new SelfTelemetryClassifier(true, "/bootui", "/bootui/api"),
                pools == null ? new ConnectionPoolService(null, exposure) : pools,
                new UnsatisfiedInstance<>(),
                rest,
                kafka,
                rabbit);
    }

    private static RestClientTraceRecorder restRecorder() {
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(true, true, false, false, 200, 1000, 256, 256, 5);
        recorder.registerClientCustomization("QuarkusRestClient");
        return recorder;
    }

    private static ConnectionPoolService pools(String jdbcUrl) {
        ConnectionPoolProvider provider = () -> List.of(new ConnectionPoolInfo(
                "default",
                "default",
                jdbcUrl,
                "app",
                "org.postgresql.Driver",
                1,
                10,
                30_000,
                600_000,
                1_800_000,
                -1,
                -1,
                false,
                true,
                true,
                null,
                null));
        return new ConnectionPoolService(provider, new QuarkusExposurePolicy(config(Map.of())));
    }

    private static CapturedHttpExchange captured(String method, String uri, int status) {
        return new CapturedHttpExchange(
                Instant.parse("2026-06-14T10:00:00Z"),
                method,
                URI.create(uri),
                status,
                12L,
                "127.0.0.1",
                null,
                null,
                Map.of(),
                Map.of(),
                null);
    }

    private static SmallRyeConfig available(String... presentKeys) {
        Map<String, String> properties = new HashMap<>();
        for (String key : presentKeys) {
            properties.put(key, "true");
        }
        return config(properties);
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    /** Stands in for an absent optional bean, exactly as Arc resolves one. */
    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no SqlTraceRecorder bean produced in this test");
        }
    }
}
