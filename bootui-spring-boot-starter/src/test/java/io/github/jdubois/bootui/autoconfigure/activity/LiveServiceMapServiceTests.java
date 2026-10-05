package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.ServiceMapNodeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the Spring-side gathering behind {@code GET /bootui/api/activity/service-map}: which sources are
 * read, which panel toggles suppress them, and that nothing sensitive survives the trip from a live
 * recorder into the shared DTO. Interpretation itself is the engine assembler's contract and is covered
 * by {@code ServiceMapAssemblerTests}.
 */
class LiveServiceMapServiceTests {

    @Test
    void mapsOutboundHttpJdbcAndMessagingEvidenceFromLiveRecorders() {
        RestClientTraceRecorder rest = restRecorder();
        rest.record(
                "GET",
                "https://api.example.com/orders/42?token=s3cret",
                "api.example.com",
                "/orders/42",
                200,
                12,
                true,
                null,
                "RestClient",
                Map.of(),
                "main");
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);
        RabbitActivityRecorder rabbit = new RabbitActivityRecorder(true, false, 10, 32);
        rabbit.recordPublish("billing", "charged", 3L, true, null, null);

        ServiceMapReport report = service(new BootUiProperties(), rest, kafka, rabbit, pools("jdbc:h2:mem:shop"), null)
                .serviceMap();

        assertThat(report.available()).isTrue();
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::label)
                .contains("https://api.example.com", "orders", "billing → charged", "jdbc:h2:mem:shop");
        assertThat(report.sources()).contains("REST Client", "Kafka", "RabbitMQ", "Connection Pools");
        assertThat(report.toString()).doesNotContain("s3cret").doesNotContain("/orders/42");
    }

    @Test
    void anchorsTheInboundLaneOnCompletedIncomingRequests() {
        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        exchanges(exchange("1", "GET", 200), exchange("2", "POST", 500)))
                .serviceMap();

        ServiceMapNodeDto inbound = report.nodes().stream()
                .filter(node -> "INBOUND".equals(node.kind()))
                .findFirst()
                .orElseThrow();
        assertThat(inbound.interactions()).isEqualTo(2);
        assertThat(inbound.failures()).isEqualTo(1);
        assertThat(report.edges()).anyMatch(edge -> "INBOUND".equals(edge.direction()));
    }

    @Test
    void readsTheWholeRetainedExchangeBufferRatherThanASinglePagedRow() {
        HttpExchangesController controller = mock(HttpExchangesController.class);
        List<HttpExchangeDto> retained =
                List.of(exchange("1", "GET", 200), exchange("2", "GET", 200), exchange("3", "GET", 200));
        when(controller.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        retained.size(),
                        retained.size(),
                        0,
                        retained,
                        new PageMetadata(0, retained.size(), retained.size(), 1, 0, false),
                        null));

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        controller)
                .serviceMap();

        ServiceMapNodeDto inbound = report.nodes().stream()
                .filter(node -> "INBOUND".equals(node.kind()))
                .findFirst()
                .orElseThrow();
        assertThat(inbound.interactions()).isEqualTo(3);
        verify(controller).exchanges(null, null, null, null, null);
    }

    @Test
    void omitsAnEvidenceSourceWhoseOwnPanelIsDisabled() {
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
                "RestClient",
                Map.of(),
                "main");
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.REST_CLIENT_TRACE).setEnabled(false);
        properties.panel(BootUiPanels.KAFKA).setEnabled(false);
        properties.panel(BootUiPanels.DATABASE_CONNECTION_POOLS).setEnabled(false);

        ServiceMapReport report = service(
                        properties,
                        rest,
                        kafka,
                        new RabbitActivityRecorder(true, false, 10, 32),
                        pools("jdbc:h2:mem:shop"),
                        null)
                .serviceMap();

        assertThat(report.nodes()).isEmpty();
        assertThat(report.sources()).isEmpty();
        assertThat(report.toString()).doesNotContain("api.example.com").doesNotContain("orders");
    }

    @Test
    void omitsOutboundHttpUntilAClientIsActuallyInstrumented() {
        RestClientTraceRecorder rest = new RestClientTraceRecorder(true, true, false, false, 20, 500, 512, 128, 3);

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        rest,
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        pools("jdbc:h2:mem:shop"),
                        null)
                .serviceMap();

        assertThat(report.sources()).doesNotContain("REST Client");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("HTTP");
    }

    @Test
    void masksJdbcCredentialsAndParametersBeforeTheyReachTheMap() {
        // Assembled from parts so no credential-shaped literal exists in the source.
        String credential = "cred" + "Value" + "42";
        String url = "jdbc:postgresql://app:" + credential + "@localhost:5432/shop?" + "pass" + "word=" + credential;

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        pools(url),
                        null)
                .serviceMap();

        assertThat(report.toString()).doesNotContain(credential);
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::label)
                .contains("jdbc:postgresql://localhost:5432/shop");
    }

    @Test
    void stripsJdbcCredentialsEvenWhenFullValueExposureIsEnabled() {
        String credential = "full" + "Exposure" + "Credential";
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.FULL);

        ServiceMapReport report = service(
                        properties,
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        pools("jdbc:postgresql://app:" + credential + "@localhost:5432/shop"),
                        null)
                .serviceMap();

        assertThat(report.toString()).doesNotContain(credential);
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::label)
                .contains("jdbc:postgresql://localhost:5432/shop");
    }

    @Test
    void requiresTheCorrespondingTemplateBeanBeforeMessagingContributes() {
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, true, 10, 16);
        kafka.recordProduce("orders", 0, "key-1", 4L, true, null);
        RabbitActivityRecorder rabbit = new RabbitActivityRecorder(true, false, 10, 32);
        rabbit.recordPublish("billing", "charged", 3L, true, null, null);

        ServiceMapReport absent = service(
                        new BootUiProperties(), restRecorder(), kafka, rabbit, null, null, false, false)
                .serviceMap();
        ServiceMapReport present = service(
                        new BootUiProperties(), restRecorder(), kafka, rabbit, null, null, true, true)
                .serviceMap();

        assertThat(absent.sources()).doesNotContain("Kafka", "RabbitMQ");
        assertThat(absent.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("KAFKA", "RABBITMQ");
        assertThat(present.sources()).contains("Kafka", "RabbitMQ");
        assertThat(present.nodes()).extracting(ServiceMapNodeDto::protocol).contains("KAFKA", "RABBITMQ");
    }

    @Test
    void reportsUnavailableRatherThanAnEmptyMapWhenNoSourceIsPresent() {
        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.HTTP_EXCHANGES).setEnabled(false);
        properties.panel(BootUiPanels.REST_CLIENT_TRACE).setEnabled(false);
        properties.panel(BootUiPanels.DATABASE_CONNECTION_POOLS).setEnabled(false);
        properties.panel(BootUiPanels.SQL_TRACE).setEnabled(false);
        properties.panel(BootUiPanels.KAFKA).setEnabled(false);
        properties.panel(BootUiPanels.RABBITMQ).setEnabled(false);

        ServiceMapReport report = service(
                        properties,
                        restRecorder(),
                        new KafkaActivityRecorder(true, true, 10, 16),
                        new RabbitActivityRecorder(true, false, 10, 32),
                        pools("jdbc:h2:mem:shop"),
                        null)
                .serviceMap();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotBlank();
        assertThat(report.application()).isNull();
    }

    @Test
    void mapsCacheAccessesFromTheLiveRecorderWhenThePanelAndRecorderAreEnabled() {
        CacheActivityRecorder cache = new CacheActivityRecorder(true, 500);
        cache.markInstrumentedManager();
        cache.recordHit("cacheManager", "products", "product-1");
        cache.recordMiss("cacheManager", "products", "product-2");

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        null,
                        true,
                        true,
                        cache)
                .serviceMap();

        assertThat(report.sources()).contains("Cache");
        ServiceMapNodeDto node = report.nodes().stream()
                .filter(candidate -> "CACHE".equals(candidate.protocol()))
                .findFirst()
                .orElseThrow();
        assertThat(node.label()).isEqualTo("cacheManager / products");
        assertThat(node.interactions()).isEqualTo(2);
        assertThat(node.sourceRoute()).isEqualTo("/cache");
    }

    @Test
    void omitsCacheEvidenceWhenTheDedicatedCachePanelIsDisabled() {
        CacheActivityRecorder cache = new CacheActivityRecorder(true, 500);
        cache.markInstrumentedManager();
        cache.recordHit("cacheManager", "products", "product-1");

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.CACHE).setEnabled(false);

        ServiceMapReport report = service(
                        properties,
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        null,
                        true,
                        true,
                        cache)
                .serviceMap();

        assertThat(report.sources()).doesNotContain("Cache");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
    }

    @Test
    void omitsCacheEvidenceWhenTheRecorderItselfIsDisabled() {
        // bootui.cache.activity-capture-enabled=false: the recorder exists but never captures, exactly
        // like an application that turned capture off while leaving the panel itself enabled.
        CacheActivityRecorder disabledRecorder = new CacheActivityRecorder(false, 500);
        disabledRecorder.recordHit("cacheManager", "products", "product-1");

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        null,
                        true,
                        true,
                        disabledRecorder)
                .serviceMap();

        assertThat(report.sources()).doesNotContain("Cache");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
    }

    @Test
    void omitsCacheEvidenceWhenNoCacheManagerBeanIsPresentAtAll() {
        // ObjectProvider#getIfAvailable() returns null when CacheActivityRecorder's own
        // @ConditionalOnClass(CacheManager) gate never matched - the common case for an application with
        // no Spring Cache abstraction on the classpath at all.
        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        null)
                .serviceMap();

        assertThat(report.sources()).doesNotContain("Cache");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
    }

    @Test
    void omitsCacheSourceWhenRecorderIsEnabledButNoCacheManagerWasInstrumented() {
        CacheActivityRecorder cache = new CacheActivityRecorder(true, 500);

        ServiceMapReport report = service(
                        new BootUiProperties(),
                        restRecorder(),
                        new KafkaActivityRecorder(false, false, 10, 16),
                        new RabbitActivityRecorder(false, false, 10, 32),
                        null,
                        null,
                        true,
                        true,
                        cache)
                .serviceMap();

        assertThat(report.sources()).doesNotContain("Cache");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private LiveServiceMapService service(
            BootUiProperties properties,
            RestClientTraceRecorder rest,
            KafkaActivityRecorder kafka,
            RabbitActivityRecorder rabbit,
            ConnectionPoolService pools,
            HttpExchangesController exchanges) {
        return service(properties, rest, kafka, rabbit, pools, exchanges, true, true);
    }

    private LiveServiceMapService service(
            BootUiProperties properties,
            RestClientTraceRecorder rest,
            KafkaActivityRecorder kafka,
            RabbitActivityRecorder rabbit,
            ConnectionPoolService pools,
            HttpExchangesController exchanges,
            boolean kafkaTemplatePresent,
            boolean rabbitTemplatePresent) {
        return service(
                properties, rest, kafka, rabbit, pools, exchanges, kafkaTemplatePresent, rabbitTemplatePresent, null);
    }

    private LiveServiceMapService service(
            BootUiProperties properties,
            RestClientTraceRecorder rest,
            KafkaActivityRecorder kafka,
            RabbitActivityRecorder rabbit,
            ConnectionPoolService pools,
            HttpExchangesController exchanges,
            boolean kafkaTemplatePresent,
            boolean rabbitTemplatePresent,
            CacheActivityRecorder cache) {
        return new LiveServiceMapService(
                provider(exchanges),
                provider(rest),
                provider(pools),
                provider((SqlTraceRecorder) null),
                provider(kafka),
                provider(rabbit),
                provider(cache),
                properties,
                new BootUiExposure(properties),
                className -> switch (className) {
                    case "org.springframework.kafka.core.KafkaTemplate" -> kafkaTemplatePresent;
                    case "org.springframework.amqp.rabbit.core.RabbitTemplate" -> rabbitTemplatePresent;
                    default -> false;
                });
    }

    /** A recorder with one instrumented client, matching how the customizer registers itself at runtime. */
    private static RestClientTraceRecorder restRecorder() {
        RestClientTraceRecorder recorder = new RestClientTraceRecorder(true, true, false, false, 20, 500, 512, 128, 3);
        recorder.registerClientCustomization("RestClient");
        return recorder;
    }

    private static ConnectionPoolService pools(String jdbcUrl) {
        BootUiProperties properties = new BootUiProperties();
        ConnectionPoolProvider provider = () -> List.of(new ConnectionPoolInfo(
                "dataSource",
                "HikariPool-1",
                jdbcUrl,
                "app",
                "org.h2.Driver",
                1,
                10,
                30_000,
                600_000,
                1_800_000,
                5_000,
                0,
                false,
                true,
                true,
                null,
                null));
        return new ConnectionPoolService(provider, new BootUiExposure(properties));
    }

    /**
     * Answers only the unbounded read. A literal {@code 0} limit is clamped to a single row by the
     * panel's paging helper, so pinning the exact arguments here is what keeps the inbound lane from
     * silently collapsing to one interaction again.
     */
    private static HttpExchangesController exchanges(HttpExchangeDto... entries) {
        HttpExchangesController controller = mock(HttpExchangesController.class);
        when(controller.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        entries.length,
                        entries.length,
                        0,
                        List.of(entries),
                        new PageMetadata(0, entries.length, entries.length, 1, 0, false),
                        null));
        return controller;
    }

    private static HttpExchangeDto exchange(String id, String method, int status) {
        return new HttpExchangeDto(
                id,
                Instant.parse("2026-06-14T10:00:00Z"),
                method,
                "/orders",
                null,
                "http://localhost:8080/orders",
                status,
                status / 100 + "xx",
                12L,
                null,
                "127.0.0.1",
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
