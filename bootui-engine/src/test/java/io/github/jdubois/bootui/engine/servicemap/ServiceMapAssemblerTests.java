package io.github.jdubois.bootui.engine.servicemap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.ServiceMapEdgeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapInteractionDto;
import io.github.jdubois.bootui.core.dto.ServiceMapNodeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceMapAssemblerTests {

    private final ServiceMapAssembler assembler = new ServiceMapAssembler();

    @Test
    void reportsUnavailableWhenNoSourcePanelContributes() {
        ServiceMapReport report = assembler.assemble(ServiceMapSources.empty(), 1_000L);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("No service map source is available");
        assertThat(report.application()).isNull();
        assertThat(report.nodes()).isEmpty();
        assertThat(report.edges()).isEmpty();
        assertThat(report.generatedAt()).isEqualTo(1_000L);
    }

    @Test
    void centersTheApplicationAndAnchorsEveryEdgeOnIt() {
        ServiceMapReport report = assembler.assemble(sources()
                .inbound(exchange("1", 100, "GET", 200))
                .http(call(1, 200, "GET", "https://api.example.com/x", 200, true))
                .build());

        assertThat(report.application().id()).isEqualTo(ServiceMapAssembler.APPLICATION_NODE_ID);
        assertThat(report.application().kind()).isEqualTo("APPLICATION");
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::id)
                .doesNotContain(ServiceMapAssembler.APPLICATION_NODE_ID);
        assertThat(report.edges())
                .allSatisfy(edge -> assertThat(edge.fromId().equals(ServiceMapAssembler.APPLICATION_NODE_ID)
                                || edge.toId().equals(ServiceMapAssembler.APPLICATION_NODE_ID))
                        .isTrue());
    }

    @Test
    void foldsCompletedIncomingRequestsIntoOneGenericInboundLane() {
        ServiceMapReport report = assembler.assemble(sources()
                .inbound(exchange("1", 100, "GET", 200))
                .inbound(exchange("2", 200, "POST", 500))
                .inbound(exchange("3", 300, "get", 404))
                .build());

        ServiceMapNodeDto inbound = node(report, ServiceMapAssembler.INBOUND_NODE_ID);
        assertThat(inbound.kind()).isEqualTo("INBOUND");
        assertThat(inbound.label()).isEqualTo("Local HTTP clients");
        assertThat(inbound.interactions()).isEqualTo(3);
        // Only the 500 is a retained failure; a 404 is the caller's request being rejected.
        assertThat(inbound.failures()).isEqualTo(1);
        assertThat(inbound.distinctOperations()).isEqualTo(2);
        assertThat(inbound.lastSeen()).isEqualTo(300L);
        assertThat(inbound.outcome()).isEqualTo("RETAINED_FAILURES");

        ServiceMapEdgeDto edge =
                edge(report, ServiceMapAssembler.INBOUND_NODE_ID, ServiceMapAssembler.APPLICATION_NODE_ID);
        assertThat(edge.direction()).isEqualTo("INBOUND");
    }

    @Test
    void groupsOutboundHttpByOriginAndNeverCarriesPathOrQuery() {
        ServiceMapReport report = assembler.assemble(sources()
                .http(call(1, 100, "GET", "https://api.example.com/orders/42?token=s3cret", 200, true))
                .http(call(2, 200, "POST", "https://api.example.com/orders?token=s3cret", 503, true))
                .http(call(3, 300, "GET", "https://other.example.com/health", 200, true))
                .build());

        ServiceMapNodeDto api = node(report, httpId("https://api.example.com"));
        assertThat(api.label()).isEqualTo("https://api.example.com");
        assertThat(api.interactions()).isEqualTo(2);
        assertThat(api.failures()).isEqualTo(1);
        assertThat(api.distinctOperations()).isEqualTo(2);
        assertThat(api.observed()).isTrue();
        assertThat(api.configured()).isFalse();
        assertThat(api.sourceRoute()).isEqualTo("/rest-client-trace");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::id).contains(httpId("https://other.example.com"));

        assertThat(report.toString()).doesNotContain("s3cret").doesNotContain("/orders");
    }

    @Test
    void countsAClientSideExceptionWithoutAStatusAsAFailure() {
        ServiceMapReport report = assembler.assemble(sources()
                .http(new RestClientTraceEntryDto(
                        1,
                        100,
                        "GET",
                        "https://down.example.com/x",
                        "down.example.com",
                        "/x",
                        null,
                        30,
                        false,
                        "Connection refused",
                        false,
                        "RestClient",
                        Map.of(),
                        null,
                        "main",
                        null))
                .build());

        ServiceMapNodeDto node = node(report, httpId("https://down.example.com"));
        assertThat(node.failures()).isEqualTo(1);
        assertThat(node.outcome()).isEqualTo("RETAINED_FAILURES");
        assertThat(report.toString()).doesNotContain("Connection refused");
    }

    @Test
    void reportsAConfiguredPoolWithNoTrafficAsConfiguredButNotObserved() {
        ServiceMapReport report = assembler.assemble(sources()
                .pool(pool("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                .build());

        ServiceMapNodeDto node =
                node(report, poolId("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"));
        assertThat(node.configured()).isTrue();
        assertThat(node.observed()).isFalse();
        assertThat(node.interactions()).isZero();
        assertThat(node.lastSeen()).isNull();
        assertThat(node.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(node.label()).isEqualTo("jdbc:postgresql://localhost:5432/shop");
        assertThat(node.note()).contains("No statement evidence is retained for it");
    }

    @Test
    void distinguishesAbsentStatementEvidenceFromDisabledSqlTracing() {
        ServiceMapSources sqlDisabled = new ServiceMapSources(
                false,
                List.of(),
                false,
                List.of(),
                true,
                List.of(pool("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop")),
                false,
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of());

        ServiceMapReport report = assembler.assemble(sqlDisabled, 5L);

        assertThat(node(report, poolId("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                        .note())
                .contains("SQL tracing is not contributing evidence");
    }

    @Test
    void attributesStatementsToTheSinglePoolWhenAttributionIsUnambiguous() {
        ServiceMapReport report = assembler.assemble(sources()
                .pool(pool("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                .sqlDataSource("dataSource")
                .sql(statement(1, 100, "SELECT", true, 4))
                .sql(statement(2, 200, "INSERT", false, 9))
                .build());

        ServiceMapNodeDto node =
                node(report, poolId("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"));
        assertThat(node.configured()).isTrue();
        assertThat(node.observed()).isTrue();
        assertThat(node.interactions()).isEqualTo(2);
        assertThat(node.failures()).isEqualTo(1);
        assertThat(node.distinctOperations()).isEqualTo(2);
        assertThat(node.lastSeen()).isEqualTo(200L);
        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::id)
                .doesNotContain(ServiceMapAssembler.SQL_AGGREGATE_NODE_ID);
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("summarized separately"));
    }

    @Test
    void neverFabricatesStatementAttributionWhenSeveralPoolsAreConfigured() {
        ServiceMapReport report = assembler.assemble(sources()
                .pool(pool("primary", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                .pool(pool("reporting", "HikariPool-2", "jdbc:postgresql://localhost:5432/reports"))
                .sqlDataSource("primary")
                .sqlDataSource("reporting")
                .sql(statement(1, 100, "SELECT", true, 4))
                .build());

        String primaryId = poolId("primary", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop");
        String reportingId = poolId("reporting", "HikariPool-2", "jdbc:postgresql://localhost:5432/reports");
        assertThat(node(report, primaryId).observed()).isFalse();
        assertThat(node(report, reportingId).observed()).isFalse();
        assertThat(node(report, primaryId).note()).contains("reported separately");

        ServiceMapNodeDto aggregate = node(report, ServiceMapAssembler.SQL_AGGREGATE_NODE_ID);
        assertThat(aggregate.label()).isEqualTo("SQL statements");
        assertThat(aggregate.configured()).isFalse();
        assertThat(aggregate.observed()).isTrue();
        assertThat(aggregate.interactions()).isEqualTo(1);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("2 connection pools are configured"));
    }

    @Test
    void neverAttributesStatementsWhenTheOnlyTracedDatasourceDoesNotMatchTheOnlyPool() {
        ServiceMapReport report = assembler.assemble(sources()
                .pool(pool("primary", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                .sqlDataSource("legacyDataSource")
                .sql(statement(1, 100, "SELECT", true, 4))
                .build());

        assertThat(node(report, poolId("primary", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"))
                        .observed())
                .isFalse();
        assertThat(node(report, ServiceMapAssembler.SQL_AGGREGATE_NODE_ID).observed())
                .isTrue();
        assertThat(report.warnings())
                .anyMatch(warning -> warning.contains("cannot be matched reliably to the configured pool"));
    }

    @Test
    void summarizesStatementsSeparatelyWhenNoPoolMetadataIsAvailable() {
        ServiceMapReport report = assembler.assemble(
                sources().sql(statement(1, 100, "SELECT", true, 4)).build());

        ServiceMapNodeDto aggregate = node(report, ServiceMapAssembler.SQL_AGGREGATE_NODE_ID);
        assertThat(aggregate.observed()).isTrue();
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("no connection pool metadata is available"));
    }

    @Test
    void neverExposesSqlTextOrBoundParameters() {
        ServiceMapReport report = assembler.assemble(sources()
                .sql(new SqlTraceEntryDto(
                        1,
                        100,
                        "select * from users where ssn = ?",
                        "PREPARED",
                        "SELECT",
                        4,
                        true,
                        null,
                        1L,
                        0,
                        "conn-1",
                        "main",
                        false,
                        List.of("123-45-6789"),
                        null,
                        "Repo.find(Repo.java:10)"))
                .build());

        assertThat(report.toString())
                .doesNotContain("select * from users")
                .doesNotContain("123-45-6789")
                .doesNotContain("Repo.java");
    }

    @Test
    void mapsProducedKafkaTopicsButNeverConsumedOnes() {
        ServiceMapReport report = assembler.assemble(sources()
                .kafka(kafka(1, 100, KafkaActivityRecorder.Direction.PRODUCE, "orders", true))
                .kafka(kafka(2, 200, KafkaActivityRecorder.Direction.CONSUME, "payments", true))
                .build());

        assertThat(report.nodes())
                .extracting(ServiceMapNodeDto::label)
                .contains("orders")
                .doesNotContain("payments");
        assertThat(node(report, kafkaId("orders")).distinctOperations()).isNull();
    }

    @Test
    void mapsPublishedAmqpDestinationsButNeverConsumedOnes() {
        ServiceMapReport report = assembler.assemble(sources()
                .rabbit(rabbit(1, 100, RabbitActivityRecorder.Direction.PUBLISH, "orders", "created", true))
                .rabbit(rabbit(2, 200, RabbitActivityRecorder.Direction.CONSUME, "billing", "charged", true))
                .build());

        assertThat(report.nodes()).extracting(ServiceMapNodeDto::label).contains("orders → created");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::label).doesNotContain("billing → charged");
    }

    @Test
    void groupsLongHttpKafkaAndRabbitValuesByCompleteIdentityAndTruncatesOnlyLabels() {
        String sharedPrefix = "x".repeat(ServiceMapIdentities.MAX_IDENTITY_LENGTH);
        String firstOrigin = "https://" + sharedPrefix + "-east.example";
        String secondOrigin = "https://" + sharedPrefix + "-west.example";
        String firstTopic = "orders-" + sharedPrefix + "-east";
        String secondTopic = "orders-" + sharedPrefix + "-west";
        String firstExchange = "exchange-" + sharedPrefix + "-east";
        String secondExchange = "exchange-" + sharedPrefix + "-west";
        String firstDestination = firstExchange + " → created";
        String secondDestination = secondExchange + " → created";

        ServiceMapReport report = assembler.assemble(sources()
                .http(call(1, 100, "GET", firstOrigin + "/orders", 200, true))
                .http(call(2, 200, "GET", secondOrigin + "/orders", 500, false))
                .kafka(kafka(1, 100, KafkaActivityRecorder.Direction.PRODUCE, firstTopic, true))
                .kafka(kafka(2, 200, KafkaActivityRecorder.Direction.PRODUCE, secondTopic, false))
                .rabbit(rabbit(1, 100, RabbitActivityRecorder.Direction.PUBLISH, firstExchange, "created", true))
                .rabbit(rabbit(2, 200, RabbitActivityRecorder.Direction.PUBLISH, secondExchange, "created", false))
                .build());

        assertThat(report.nodes()).hasSize(6);
        assertThat(report.edges()).hasSize(6);
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::id).doesNotHaveDuplicates();
        assertThat(node(report, httpId(firstOrigin)).label()).isEqualTo(ServiceMapIdentities.truncate(firstOrigin));
        assertThat(node(report, httpId(secondOrigin)).label()).isEqualTo(ServiceMapIdentities.truncate(secondOrigin));
        assertThat(node(report, kafkaId(firstTopic)).label()).isEqualTo(ServiceMapIdentities.truncate(firstTopic));
        assertThat(node(report, kafkaId(secondTopic)).label()).isEqualTo(ServiceMapIdentities.truncate(secondTopic));
        assertThat(node(report, rabbitId(firstExchange, "created")).label())
                .isEqualTo(ServiceMapIdentities.truncate(firstDestination));
        assertThat(node(report, rabbitId(secondExchange, "created")).label())
                .isEqualTo(ServiceMapIdentities.truncate(secondDestination));
        assertThat(report.nodes())
                .allSatisfy(node -> assertThat(node.interactions()).isOne());
        assertThat(report.truncation().truncated()).isFalse();
        assertThat(report.truncation().dependenciesShown()).isEqualTo(6);
        assertThat(report.truncation().dependenciesOmitted()).isZero();
        assertThat(node(report, kafkaId(firstTopic)).failures()).isZero();
        assertThat(node(report, kafkaId(secondTopic)).failures()).isOne();
    }

    @Test
    void omitsEvidenceFromDisabledSourcePanels() {
        ServiceMapSources disabled = new ServiceMapSources(
                false,
                List.of(exchange("1", 100, "GET", 200)),
                false,
                List.of(call(1, 100, "GET", "https://api.example.com/x", 200, true)),
                false,
                List.of(pool("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop")),
                false,
                List.of(statement(1, 100, "SELECT", true, 4)),
                List.of(),
                false,
                List.of(kafka(1, 100, KafkaActivityRecorder.Direction.PRODUCE, "orders", true)),
                false,
                List.of(rabbit(1, 100, RabbitActivityRecorder.Direction.PUBLISH, "orders", "created", true)),
                false,
                List.of(cacheEvent(1, 100, "cacheManager", "products", CacheActivityOperation.HIT, null)));

        ServiceMapReport report = assembler.assemble(disabled, 5L);

        assertThat(report.available()).isFalse();
        assertThat(report.nodes()).isEmpty();
        assertThat(report.toString())
                .doesNotContain("api.example.com")
                .doesNotContain("orders")
                .doesNotContain("products");
    }

    @Test
    void keepsRabbitTuplesSeparateWhenTheirDisplayLabelsAreAmbiguous() {
        ServiceMapReport report = assembler.assemble(sources()
                .rabbit(rabbit(1, 100, RabbitActivityRecorder.Direction.PUBLISH, "a → b", "c", true))
                .rabbit(rabbit(2, 200, RabbitActivityRecorder.Direction.PUBLISH, "a", "b → c", false))
                .rabbit(rabbit(3, 300, RabbitActivityRecorder.Direction.PUBLISH, "(default exchange)", "created", true))
                .rabbit(rabbit(4, 400, RabbitActivityRecorder.Direction.PUBLISH, "", "created", true))
                .build());

        assertThat(report.nodes()).hasSize(4);
        assertThat(report.edges()).hasSize(4);
        assertThat(node(report, rabbitId("a → b", "c")).interactions()).isOne();
        assertThat(node(report, rabbitId("a", "b → c")).failures()).isOne();
        assertThat(node(report, rabbitId("(default exchange)", "created")).interactions())
                .isOne();
        assertThat(node(report, rabbitId("", "created")).interactions()).isOne();
    }

    @Test
    void capsDependenciesAndReportsTheOmissionVisibly() {
        SourcesBuilder builder = sources();
        for (int index = 0; index < ServiceMapLimits.MAX_DEPENDENCIES + 5; index++) {
            builder.http(call(index, 1_000L + index, "GET", "https://host-" + index + ".example.com/x", 200, true));
        }

        ServiceMapReport report = assembler.assemble(builder.build());

        assertThat(report.nodes()).hasSize(ServiceMapLimits.MAX_DEPENDENCIES);
        assertThat(report.edges()).hasSize(ServiceMapLimits.MAX_DEPENDENCIES);
        assertThat(report.truncation().truncated()).isTrue();
        assertThat(report.truncation().dependenciesOmitted()).isEqualTo(5);
        assertThat(report.truncation().dependenciesShown()).isEqualTo(ServiceMapLimits.MAX_DEPENDENCIES);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("not shown"));
    }

    @Test
    void keepsConfiguredDependenciesAheadOfABurstOfOneOffOrigins() {
        SourcesBuilder builder =
                sources().pool(pool("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop"));
        for (int index = 0; index < ServiceMapLimits.MAX_DEPENDENCIES + 10; index++) {
            builder.http(call(index, 9_000L + index, "GET", "https://host-" + index + ".example.com/x", 200, true));
        }

        ServiceMapReport report = assembler.assemble(builder.build());

        String poolId = poolId("dataSource", "HikariPool-1", "jdbc:postgresql://localhost:5432/shop");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::id).contains(poolId);
        assertThat(report.nodes().get(0).id()).isEqualTo(poolId);
    }

    @Test
    void capsRetainedInteractionsPerEdgeNewestFirstWithStableIds() {
        SourcesBuilder builder = sources();
        for (int index = 0; index < ServiceMapLimits.MAX_RECENT_INTERACTIONS + 4; index++) {
            builder.http(call(index, 1_000L + index, "GET", "https://api.example.com/x", 200, true));
        }

        ServiceMapReport report = assembler.assemble(builder.build());
        ServiceMapEdgeDto edge =
                edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, httpId("https://api.example.com"));

        assertThat(edge.interactions()).isEqualTo(ServiceMapLimits.MAX_RECENT_INTERACTIONS + 4);
        assertThat(edge.recentInteractions()).hasSize(ServiceMapLimits.MAX_RECENT_INTERACTIONS);
        assertThat(edge.recentInteractions().get(0).timestamp())
                .isGreaterThan(edge.recentInteractions().get(1).timestamp());
        assertThat(edge.recentInteractions())
                .extracting(interaction -> interaction.id())
                .doesNotHaveDuplicates();
        assertThat(edge.recentInteractions().get(0).id()).startsWith("http:");
        assertThat(report.truncation().interactionLimit()).isEqualTo(ServiceMapLimits.MAX_RECENT_INTERACTIONS);
    }

    @Test
    void keepsInteractionIdsStableAcrossRepeatedAssembliesOfTheSameEvidence() {
        ServiceMapSources evidence = sources()
                .http(call(7, 100, "GET", "https://api.example.com/x", 200, true))
                .build();

        List<String> first = edge(
                        assembler.assemble(evidence),
                        ServiceMapAssembler.APPLICATION_NODE_ID,
                        httpId("https://api.example.com"))
                .recentInteractions()
                .stream()
                .map(interaction -> interaction.id())
                .toList();
        List<String> second = edge(
                        assembler.assemble(evidence),
                        ServiceMapAssembler.APPLICATION_NODE_ID,
                        httpId("https://api.example.com"))
                .recentInteractions()
                .stream()
                .map(interaction -> interaction.id())
                .toList();

        assertThat(first).isEqualTo(second).containsExactly("http:7");
    }

    @Test
    void namesOnlyTheSourcesThatActuallyContributed() {
        ServiceMapReport report = assembler.assemble(sources()
                .inbound(exchange("1", 100, "GET", 200))
                .kafka(kafka(1, 200, KafkaActivityRecorder.Direction.PRODUCE, "orders", true))
                .build());

        assertThat(report.sources()).containsExactlyInAnyOrder("HTTP Exchanges", "Kafka");
    }

    @Test
    void warnsWhenEvidenceCannotBeReducedToASafeIdentity() {
        ServiceMapReport report = assembler.assemble(sources()
                .http(call(1, 100, "GET", "/relative/only", 200, true))
                .kafka(kafka(2, 200, KafkaActivityRecorder.Direction.PRODUCE, "  ", true))
                .rabbit(rabbit(3, 300, RabbitActivityRecorder.Direction.PUBLISH, null, null, true))
                .build());

        assertThat(report.nodes()).isEmpty();
        assertThat(report.warnings()).hasSize(3);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("safe origin"));
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("carried no topic"));
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("no exchange or routing key"));
    }

    // ── Cache protocol ───────────────────────────────────────────────────────────────────────────

    @Test
    void groupsCacheAccessesBySafeCacheManagerAndCacheNameIdentity() {
        ServiceMapReport report = assembler.assemble(sources()
                .cache(cacheEvent(1, 100, "cacheManager", "products", CacheActivityOperation.HIT, null))
                .cache(cacheEvent(2, 200, "cacheManager", "products", CacheActivityOperation.MISS, null))
                .cache(cacheEvent(3, 300, "cacheManager", "orders", CacheActivityOperation.HIT, null))
                .cache(cacheEvent(4, 400, "reportingManager", "products", CacheActivityOperation.HIT, null))
                .build());

        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).containsOnly("CACHE");
        assertThat(report.nodes()).hasSize(3);
        ServiceMapNodeDto products = node(report, cacheId("cacheManager", "products"));
        assertThat(products.label()).isEqualTo("cacheManager / products");
        assertThat(products.interactions()).isEqualTo(2);
        assertThat(node(report, cacheId("cacheManager", "orders")).interactions())
                .isOne();
        // A same-named cache under a different manager is a distinct node, never folded together.
        assertThat(node(report, cacheId("reportingManager", "products")).interactions())
                .isOne();
        assertThat(report.sources()).contains("Cache");
    }

    @Test
    void mapsAllFiveCacheOperationsAsSafeCoarseLabelsWithNoRawKeyOrValue() {
        ServiceMapReport report = assembler.assemble(sources()
                .cache(cacheEvent(1, 100, "cacheManager", "products", CacheActivityOperation.HIT, null))
                .cache(cacheEvent(2, 200, "cacheManager", "products", CacheActivityOperation.MISS, null))
                .cache(cacheEvent(3, 300, "cacheManager", "products", CacheActivityOperation.PUT, null))
                .cache(cacheEvent(4, 400, "cacheManager", "products", CacheActivityOperation.EVICT, null))
                .cache(cacheEvent(5, 500, "cacheManager", "products", CacheActivityOperation.CLEAR, null))
                .build());

        ServiceMapEdgeDto edge =
                edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, cacheId("cacheManager", "products"));
        assertThat(edge.recentInteractions())
                .extracting(ServiceMapInteractionDto::operation)
                .containsExactlyInAnyOrder("HIT", "MISS", "PUT", "EVICT", "CLEAR");
        // Fixture events all carry the same distinctive key hash; proving it never reaches the report is
        // what proves no key (or a fortiori no value) is ever mapped.
        assertThat(report.toString()).doesNotContain("deadbeefcafebabe");
    }

    @Test
    void neverTreatsACacheMissAsARetainedFailureSinceItIsANormalOutcome() {
        ServiceMapReport report = assembler.assemble(sources()
                .cache(cacheEvent(1, 100, "cacheManager", "products", CacheActivityOperation.MISS, null))
                .cache(cacheEvent(2, 200, "cacheManager", "products", CacheActivityOperation.MISS, null))
                .build());

        ServiceMapNodeDto node = node(report, cacheId("cacheManager", "products"));
        assertThat(node.failures()).isZero();
        assertThat(node.outcome()).isEqualTo("OBSERVED_OK");
    }

    @Test
    void cacheDependenciesAreObservedOnlyAndDeepLinkToTheCachePanel() {
        ServiceMapReport report = assembler.assemble(sources()
                .cache(cacheEvent(1, 100, "cacheManager", "products", CacheActivityOperation.HIT, null))
                .build());

        ServiceMapNodeDto node = node(report, cacheId("cacheManager", "products"));
        assertThat(node.configured()).isFalse();
        assertThat(node.observed()).isTrue();
        assertThat(node.sourceRoute()).isEqualTo("/cache");
        assertThat(node.sourceLabel()).isEqualTo("Cache");
        assertThat(node.distinctOperations()).isEqualTo(1);
    }

    @Test
    void omitsCacheAccessesWithNoCacheNameAndWarnsVisibly() {
        ServiceMapReport report = assembler.assemble(sources()
                .cache(cacheEvent(1, 100, "cacheManager", "  ", CacheActivityOperation.HIT, null))
                .build());

        assertThat(report.nodes()).isEmpty();
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("carried no cache name"));
    }

    @Test
    void reportsNoCacheSourceWhenTheRecorderContributesNoEvents() {
        ServiceMapSources cacheAvailableButEmpty = new ServiceMapSources(
                true,
                List.of(exchange("1", 100, "GET", 200)),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                true,
                List.of());

        ServiceMapReport report = assembler.assemble(cacheAvailableButEmpty);

        assertThat(report.sources()).doesNotContain("Cache");
        assertThat(report.nodes()).extracting(ServiceMapNodeDto::protocol).doesNotContain("CACHE");
    }

    @Test
    void cacheInteractionsRespectTheSharedRecentInteractionCap() {
        SourcesBuilder builder = sources();
        for (int index = 0; index < ServiceMapLimits.MAX_RECENT_INTERACTIONS + 4; index++) {
            builder.cache(
                    cacheEvent(index, 1_000L + index, "cacheManager", "products", CacheActivityOperation.HIT, null));
        }

        ServiceMapReport report = assembler.assemble(builder.build());
        ServiceMapEdgeDto edge =
                edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, cacheId("cacheManager", "products"));

        assertThat(edge.interactions()).isEqualTo(ServiceMapLimits.MAX_RECENT_INTERACTIONS + 4);
        assertThat(edge.recentInteractions()).hasSize(ServiceMapLimits.MAX_RECENT_INTERACTIONS);
    }

    // ── Flow correlation ─────────────────────────────────────────────────────────────────────────

    @Test
    void sharesTheSameOpaqueFlowIdAcrossInboundSqlRestAndCacheOnTheSameTrace() {
        String traceId = "00000000000000000000000000000001";
        ServiceMapReport report = assembler.assemble(sources()
                .inbound(exchange("1", 100, "GET", 200, traceId))
                .sql(statement(1, 150, "SELECT", true, 4, traceId))
                .http(call(1, 180, "GET", "https://api.example.com/x", 200, true, traceId))
                .cache(cacheEvent(1, 120, "cacheManager", "products", CacheActivityOperation.HIT, traceId))
                .build());

        String inboundFlow = onlyInteraction(
                        edge(report, ServiceMapAssembler.INBOUND_NODE_ID, ServiceMapAssembler.APPLICATION_NODE_ID))
                .flowId();
        String sqlFlow = onlyInteraction(edge(
                        report, ServiceMapAssembler.APPLICATION_NODE_ID, ServiceMapAssembler.SQL_AGGREGATE_NODE_ID))
                .flowId();
        String httpFlow = onlyInteraction(
                        edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, httpId("https://api.example.com")))
                .flowId();
        String cacheFlow = onlyInteraction(
                        edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, cacheId("cacheManager", "products")))
                .flowId();

        assertThat(inboundFlow).isNotNull();
        assertThat(sqlFlow).isEqualTo(inboundFlow);
        assertThat(httpFlow).isEqualTo(inboundFlow);
        assertThat(cacheFlow).isEqualTo(inboundFlow);
    }

    @Test
    void flowIdIsNullWhenNoTraceWasActive() {
        ServiceMapReport report = assembler.assemble(
                sources().inbound(exchange("1", 100, "GET", 200, null)).build());

        String flowId = onlyInteraction(
                        edge(report, ServiceMapAssembler.INBOUND_NODE_ID, ServiceMapAssembler.APPLICATION_NODE_ID))
                .flowId();

        assertThat(flowId).isNull();
    }

    @Test
    void blankTraceIdAlsoYieldsANullFlowIdRatherThanASyntheticOne() {
        ServiceMapReport report = assembler.assemble(
                sources().inbound(exchange("1", 100, "GET", 200, "   ")).build());

        String flowId = onlyInteraction(
                        edge(report, ServiceMapAssembler.INBOUND_NODE_ID, ServiceMapAssembler.APPLICATION_NODE_ID))
                .flowId();

        assertThat(flowId).isNull();
    }

    @Test
    void neverExposesTheRawTraceIdAndDifferentTracesProduceDifferentFlowIds() {
        String firstTrace = "very-identifiable-trace-id-alpha";
        String secondTrace = "very-identifiable-trace-id-beta";
        ServiceMapReport report = assembler.assemble(sources()
                .inbound(exchange("1", 100, "GET", 200, firstTrace))
                .inbound(exchange("2", 200, "GET", 200, secondTrace))
                .build());

        List<ServiceMapInteractionDto> interactions = edge(
                        report, ServiceMapAssembler.INBOUND_NODE_ID, ServiceMapAssembler.APPLICATION_NODE_ID)
                .recentInteractions();
        assertThat(interactions).hasSize(2);
        String firstFlowId = interactions.stream()
                .filter(i -> i.id().equals("inbound:1"))
                .findFirst()
                .orElseThrow()
                .flowId();
        String secondFlowId = interactions.stream()
                .filter(i -> i.id().equals("inbound:2"))
                .findFirst()
                .orElseThrow()
                .flowId();

        assertThat(firstFlowId).isNotNull().isNotEqualTo(secondFlowId);
        assertThat(firstFlowId).isNotEqualTo(firstTrace);
        assertThat(secondFlowId).isNotEqualTo(secondTrace);
        assertThat(report.toString()).doesNotContain(firstTrace).doesNotContain(secondTrace);
    }

    @Test
    void messagingInteractionsNeverCarryAFlowIdSinceTheyCarryNoTraceIdAtCaptureTime() {
        ServiceMapReport report = assembler.assemble(sources()
                .kafka(kafka(1, 100, KafkaActivityRecorder.Direction.PRODUCE, "orders", true))
                .rabbit(rabbit(1, 100, RabbitActivityRecorder.Direction.PUBLISH, "billing", "charged", true))
                .build());

        assertThat(onlyInteraction(edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, kafkaId("orders")))
                        .flowId())
                .isNull();
        assertThat(onlyInteraction(
                                edge(report, ServiceMapAssembler.APPLICATION_NODE_ID, rabbitId("billing", "charged")))
                        .flowId())
                .isNull();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private static ServiceMapNodeDto node(ServiceMapReport report, String id) {
        Optional<ServiceMapNodeDto> node = report.nodes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst();
        assertThat(node).as("node %s", id).isPresent();
        return node.get();
    }

    private static ServiceMapEdgeDto edge(ServiceMapReport report, String fromId, String toId) {
        Optional<ServiceMapEdgeDto> edge = report.edges().stream()
                .filter(candidate ->
                        candidate.fromId().equals(fromId) && candidate.toId().equals(toId))
                .findFirst();
        assertThat(edge).as("edge %s -> %s", fromId, toId).isPresent();
        return edge.get();
    }

    /** Convenience for flow-correlation tests that only ever put one interaction on an edge. */
    private static ServiceMapInteractionDto onlyInteraction(ServiceMapEdgeDto edge) {
        assertThat(edge.recentInteractions())
                .as("interactions on edge %s", edge.id())
                .hasSize(1);
        return edge.recentInteractions().get(0);
    }

    private static String httpId(String origin) {
        return ServiceMapIdentities.stableId("http:", origin);
    }

    private static String kafkaId(String topic) {
        return ServiceMapIdentities.stableId("kafka:topic:", topic);
    }

    private static String rabbitId(String exchange, String routingKey) {
        return ServiceMapIdentities.stableId("rabbitmq:", ServiceMapIdentities.rabbitIdentity(exchange, routingKey));
    }

    private static String poolId(String beanName, String poolName, String target) {
        return ServiceMapIdentities.stableId("jdbc:pool:", String.join("\u0000", beanName, poolName, target));
    }

    private static String cacheId(String managerName, String cacheName) {
        return ServiceMapIdentities.stableId("cache:", (managerName == null ? "" : managerName) + "\u0000" + cacheName);
    }

    private static HttpExchangeDto exchange(String id, long timestamp, String method, int status) {
        return exchange(id, timestamp, method, status, null);
    }

    private static HttpExchangeDto exchange(String id, long timestamp, String method, int status, String traceId) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(timestamp),
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
                traceId,
                List.of(),
                List.of());
    }

    private static RestClientTraceEntryDto call(
            long id, long timestamp, String method, String uri, Integer status, boolean success) {
        return call(id, timestamp, method, uri, status, success, null);
    }

    private static RestClientTraceEntryDto call(
            long id, long timestamp, String method, String uri, Integer status, boolean success, String traceId) {
        return new RestClientTraceEntryDto(
                id,
                timestamp,
                method,
                uri,
                "host",
                "/path",
                status,
                12,
                success,
                null,
                false,
                "RestClient",
                Map.of(),
                traceId,
                "main",
                null);
    }

    private static HikariPoolDto pool(String beanName, String poolName, String jdbcUrl) {
        return new HikariPoolDto(
                beanName,
                poolName,
                jdbcUrl,
                "******",
                "org.postgresql.Driver",
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
                null);
    }

    private static SqlTraceEntryDto statement(
            long id, long timestamp, String category, boolean success, long durationMillis) {
        return statement(id, timestamp, category, success, durationMillis, null);
    }

    private static SqlTraceEntryDto statement(
            long id, long timestamp, String category, boolean success, long durationMillis, String traceId) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                "select 1",
                "PREPARED",
                category,
                durationMillis,
                success,
                success ? null : "boom",
                1L,
                0,
                "conn-1",
                "main",
                false,
                List.of(),
                traceId,
                null);
    }

    private static KafkaActivityRecorder.CapturedMessage kafka(
            long id, long timestamp, KafkaActivityRecorder.Direction direction, String topic, boolean success) {
        return new KafkaActivityRecorder.CapturedMessage(
                id, timestamp, direction, topic, 0, 1L, null, 3L, success, null, null, null);
    }

    private static RabbitActivityRecorder.CapturedMessage rabbit(
            long id,
            long timestamp,
            RabbitActivityRecorder.Direction direction,
            String exchange,
            String routingKey,
            boolean success) {
        return new RabbitActivityRecorder.CapturedMessage(
                id, timestamp, direction, exchange, routingKey, null, 3L, success, null, null);
    }

    private static CacheActivityEvent cacheEvent(
            long seq,
            long timestamp,
            String managerName,
            String cacheName,
            CacheActivityOperation operation,
            String traceId) {
        // keyHash is deliberately non-null and distinctive in fixtures so tests can prove it never
        // reaches the assembled report; thread is a fixed placeholder since it carries no map meaning.
        return new CacheActivityEvent(
                seq, timestamp, managerName, cacheName, operation, "deadbeefcafebabe", traceId, "main");
    }

    private static SourcesBuilder sources() {
        return new SourcesBuilder();
    }

    /** Small builder so each test only states the evidence it cares about. */
    private static final class SourcesBuilder {

        private final List<HttpExchangeDto> inbound = new ArrayList<>();
        private final List<RestClientTraceEntryDto> http = new ArrayList<>();
        private final List<HikariPoolDto> pools = new ArrayList<>();
        private final List<SqlTraceEntryDto> sql = new ArrayList<>();
        private final List<String> sqlDataSources = new ArrayList<>();
        private final List<KafkaActivityRecorder.CapturedMessage> kafka = new ArrayList<>();
        private final List<RabbitActivityRecorder.CapturedMessage> rabbit = new ArrayList<>();
        private final List<CacheActivityEvent> cache = new ArrayList<>();

        private SourcesBuilder inbound(HttpExchangeDto exchange) {
            inbound.add(exchange);
            return this;
        }

        private SourcesBuilder http(RestClientTraceEntryDto call) {
            http.add(call);
            return this;
        }

        private SourcesBuilder pool(HikariPoolDto pool) {
            pools.add(pool);
            return this;
        }

        private SourcesBuilder sql(SqlTraceEntryDto statement) {
            sql.add(statement);
            return this;
        }

        private SourcesBuilder sqlDataSource(String name) {
            sqlDataSources.add(name);
            return this;
        }

        private SourcesBuilder kafka(KafkaActivityRecorder.CapturedMessage message) {
            kafka.add(message);
            return this;
        }

        private SourcesBuilder rabbit(RabbitActivityRecorder.CapturedMessage message) {
            rabbit.add(message);
            return this;
        }

        private SourcesBuilder cache(CacheActivityEvent event) {
            cache.add(event);
            return this;
        }

        // Every source declared through this builder is treated as an enabled, contributing panel; the
        // disabled case is asserted explicitly by its own test.
        private ServiceMapSources build() {
            return new ServiceMapSources(
                    true,
                    inbound,
                    true,
                    http,
                    true,
                    pools,
                    true,
                    sql,
                    sqlDataSources,
                    true,
                    kafka,
                    true,
                    rabbit,
                    true,
                    cache);
        }
    }
}
