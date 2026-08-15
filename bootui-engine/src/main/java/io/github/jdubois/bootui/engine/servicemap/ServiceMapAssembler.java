package io.github.jdubois.bootui.engine.servicemap;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.ServiceMapEdgeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapInteractionDto;
import io.github.jdubois.bootui.core.dto.ServiceMapNodeDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.core.dto.ServiceMapTruncationDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Framework- and JSON-free assembly of the Live Flow service map from evidence BootUI already retains.
 *
 * <p>This service adds no instrumentation and performs no external work. It reads bounded, already-masked
 * buffers other panels populate, groups them by a safe identity, and reports what that evidence does and
 * does not prove. Nothing here contacts a remote system, resolves a name, or reads configuration beyond what
 * an adapter hands it.</p>
 *
 * <h2>What becomes a relationship</h2>
 *
 * <ul>
 *   <li>Completed <em>incoming</em> requests become one generic inbound lane, never per-caller nodes: BootUI
 *       has no safe, stable identity for a local HTTP client and inventing one would be a guess.</li>
 *   <li>Outbound HTTP calls group by {@code scheme://host[:port]} origin only.</li>
 *   <li>Configured JDBC pools are dependencies in their own right, whether or not any statement ran.</li>
 *   <li>Kafka <em>producer</em> records group by topic; RabbitMQ <em>publisher</em> records group by
 *       exchange/routing destination. Consumption is inbound work, so it is never drawn as an outbound
 *       dependency.</li>
 *   <li>Cache accesses (hit/miss/put/evict/clear) group by the safe cache-manager/cache-name identity that
 *       already backs the Cache panel. Only where a Spring adapter's {@code CacheActivityRecorder} is
 *       actually capturing evidence — Quarkus has no comparable interception seam for {@code quarkus-cache},
 *       so it always reports {@code cacheAvailable: false} here, exactly as it does for the Live Activity
 *       feed's {@code CACHE} entry type.</li>
 * </ul>
 *
 * <h2>Flow correlation</h2>
 *
 * <p>Every completed interaction that carries a distributed-trace id (inbound HTTP, SQL, outbound REST, and
 * cache) is stamped with an opaque {@code flowId} derived one-way from that trace id
 * ({@link ServiceMapIdentities#flowId}), so the browser can recognize interactions on different edges as one
 * causal flow through the application and sequence their motion accordingly. The raw trace id is never
 * carried by this contract. A blank trace id yields a {@code null} flowId. Messaging (Kafka, RabbitMQ)
 * carries no trace id at capture time, so it is never correlated into a flow — it remains exactly as
 * uncorrelated here as it is everywhere else in BootUI.</p>
 *
 * <h2>Honesty rules</h2>
 *
 * <ul>
 *   <li>{@code configured} and {@code observed} are always reported separately, so no-traffic never reads as
 *       no-dependency.</li>
 *   <li>Statement evidence is attributed to a pool only when attribution is unambiguous — exactly one
 *       configured pool and exactly one traced datasource whose name matches that pool. Otherwise the
 *       statements are summarized on their own aggregate node and the pools stay configured-only, with the
 *       reason surfaced as a warning. BootUI never invents a statement-to-pool relationship.</li>
 *   <li>A failure count means only that a retained interaction failed. It is evidence for debugging, never a
 *       health check of the remote system.</li>
 *   <li>Cardinality is capped before serialization and any omission is reported.</li>
 * </ul>
 */
public final class ServiceMapAssembler {

    /** Node ids are stable across refreshes so the browser can diff a map rather than rebuild it. */
    public static final String APPLICATION_NODE_ID = "app";

    public static final String INBOUND_NODE_ID = "inbound:http";

    /** Used only when statement evidence exists but cannot be honestly attributed to one pool. */
    public static final String SQL_AGGREGATE_NODE_ID = "jdbc:statements";

    static final String KIND_APPLICATION = "APPLICATION";
    static final String KIND_INBOUND = "INBOUND";
    static final String KIND_DEPENDENCY = "DEPENDENCY";

    static final String PROTOCOL_APPLICATION = "APPLICATION";
    static final String PROTOCOL_HTTP_INBOUND = "HTTP_INBOUND";
    static final String PROTOCOL_HTTP = "HTTP";
    static final String PROTOCOL_JDBC = "JDBC";
    static final String PROTOCOL_KAFKA = "KAFKA";
    static final String PROTOCOL_RABBITMQ = "RABBITMQ";
    static final String PROTOCOL_CACHE = "CACHE";

    static final String OUTCOME_NO_EVIDENCE = "NO_EVIDENCE";
    static final String OUTCOME_OBSERVED_OK = "OBSERVED_OK";
    static final String OUTCOME_RETAINED_FAILURES = "RETAINED_FAILURES";

    static final String DIRECTION_INBOUND = "INBOUND";
    static final String DIRECTION_OUTBOUND = "OUTBOUND";

    private static final String UNAVAILABLE_REASON =
            "No service map source is available. Enable HTTP exchange recording, REST client tracing, "
                    + "connection pool reporting, SQL tracing, Kafka capture, RabbitMQ capture, or cache activity "
                    + "capture to populate this map.";

    /** Assembles one map. Callers may invoke this per request; it holds no state between calls. */
    public ServiceMapReport assemble(ServiceMapSources sources) {
        return assemble(sources, System.currentTimeMillis());
    }

    /** Assembly with an explicit clock reading, so tests can pin {@code generatedAt}. */
    public ServiceMapReport assemble(ServiceMapSources sources, long generatedAt) {
        ServiceMapSources evidence = sources == null ? ServiceMapSources.empty() : sources;
        List<String> contributingSources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        boolean anySourceAvailable = evidence.inboundAvailable()
                || evidence.restClientAvailable()
                || evidence.jdbcPoolsAvailable()
                || evidence.sqlAvailable()
                || evidence.kafkaAvailable()
                || evidence.rabbitAvailable()
                || evidence.cacheAvailable();
        if (!anySourceAvailable) {
            return new ServiceMapReport(
                    false,
                    UNAVAILABLE_REASON,
                    generatedAt,
                    null,
                    List.of(),
                    List.of(),
                    emptyTruncation(),
                    List.of(),
                    List.of());
        }

        NodeBuilder inbound = collectInbound(evidence, contributingSources);
        Map<String, NodeBuilder> dependencies = new LinkedHashMap<>();
        collectOutboundHttp(evidence, dependencies, contributingSources, warnings);
        collectJdbc(evidence, dependencies, contributingSources, warnings);
        collectCache(evidence, dependencies, contributingSources, warnings);
        collectKafka(evidence, dependencies, contributingSources, warnings);
        collectRabbit(evidence, dependencies, contributingSources, warnings);

        List<NodeBuilder> ranked = rank(dependencies.values());
        int omitted = Math.max(0, ranked.size() - ServiceMapLimits.MAX_DEPENDENCIES);
        List<NodeBuilder> retained = omitted == 0 ? ranked : ranked.subList(0, ServiceMapLimits.MAX_DEPENDENCIES);
        if (omitted > 0) {
            warnings.add(omitted + " less recently used dependenc" + (omitted == 1 ? "y is" : "ies are")
                    + " not shown because the map is capped at " + ServiceMapLimits.MAX_DEPENDENCIES
                    + " dependencies. Open the source panels for the full list.");
        }

        List<ServiceMapNodeDto> nodes = new ArrayList<>();
        List<ServiceMapEdgeDto> edges = new ArrayList<>();
        if (inbound != null) {
            nodes.add(inbound.toNode());
            edges.add(inbound.toEdge(INBOUND_NODE_ID, APPLICATION_NODE_ID, DIRECTION_INBOUND));
        }
        for (NodeBuilder dependency : retained) {
            nodes.add(dependency.toNode());
            edges.add(dependency.toEdge(APPLICATION_NODE_ID, dependency.id, DIRECTION_OUTBOUND));
        }

        ServiceMapNodeDto application = new ServiceMapNodeDto(
                APPLICATION_NODE_ID,
                KIND_APPLICATION,
                PROTOCOL_APPLICATION,
                "This application",
                null,
                true,
                true,
                0,
                0,
                null,
                null,
                OUTCOME_OBSERVED_OK,
                null,
                null,
                null,
                null);

        return new ServiceMapReport(
                true,
                null,
                generatedAt,
                application,
                nodes,
                edges,
                new ServiceMapTruncationDto(
                        omitted > 0,
                        ServiceMapLimits.MAX_DEPENDENCIES,
                        retained.size(),
                        omitted,
                        ServiceMapLimits.MAX_RECENT_INTERACTIONS),
                contributingSources,
                warnings);
    }

    // ── Inbound ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Folds completed incoming requests into a single generic lane. Per-caller nodes are deliberately not
     * derived: a remote address is neither a stable identity nor safe to display here.
     */
    private NodeBuilder collectInbound(ServiceMapSources evidence, List<String> contributingSources) {
        if (!evidence.inboundAvailable() || evidence.inboundExchanges().isEmpty()) {
            return null;
        }
        NodeBuilder node = new NodeBuilder(
                INBOUND_NODE_ID,
                KIND_INBOUND,
                PROTOCOL_HTTP_INBOUND,
                "Local HTTP clients",
                "Completed incoming requests",
                false);
        node.source(BootUiPanels.HTTP_EXCHANGES, "/http-exchanges", "HTTP Exchanges");
        node.note("Incoming traffic BootUI already recorded. Callers are not identified.");
        for (HttpExchangeDto exchange : evidence.inboundExchanges()) {
            if (exchange == null || exchange.timestamp() == null) {
                continue;
            }
            // A 5xx is the application failing its caller; a 4xx is the caller's own request being
            // rejected, so only server-side failures are counted as retained failures here.
            boolean failed = exchange.status() >= 500;
            node.observe(
                    "inbound:" + exchange.id(),
                    exchange.timestamp().toEpochMilli(),
                    safeMethod(exchange.method()),
                    failed,
                    exchange.durationMs(),
                    exchange.traceId());
        }
        if (node.interactions == 0) {
            return null;
        }
        contributingSources.add("HTTP Exchanges");
        return node;
    }

    // ── Outbound HTTP ────────────────────────────────────────────────────────────────────────────

    private void collectOutboundHttp(
            ServiceMapSources evidence,
            Map<String, NodeBuilder> dependencies,
            List<String> contributingSources,
            List<String> warnings) {
        if (!evidence.restClientAvailable() || evidence.restClientCalls().isEmpty()) {
            return;
        }
        int unidentified = 0;
        boolean contributed = false;
        for (RestClientTraceEntryDto call : evidence.restClientCalls()) {
            if (call == null) {
                continue;
            }
            String origin = ServiceMapIdentities.httpOrigin(call.uri());
            if (origin == null) {
                unidentified++;
                continue;
            }
            NodeBuilder node = dependencies.computeIfAbsent(
                    ServiceMapIdentities.stableId("http:", origin),
                    id -> new NodeBuilder(
                                    id,
                                    KIND_DEPENDENCY,
                                    PROTOCOL_HTTP,
                                    ServiceMapIdentities.truncate(origin),
                                    "Outbound HTTP",
                                    false)
                            .source(BootUiPanels.REST_CLIENT_TRACE, "/rest-client-trace", "REST Client")
                            .note("Grouped by origin only. Request paths and query values are never mapped."));
            // A 4xx/5xx response is a remote-side failure worth showing; a client-side exception (no status)
            // is a failure too. Both are retained evidence, not a live health signal.
            boolean failed = !call.success() || (call.status() != null && call.status() >= 400);
            node.observe(
                    "http:" + call.id(),
                    call.timestamp(),
                    safeMethod(call.method()),
                    failed,
                    call.durationMillis(),
                    call.traceId());
            contributed = true;
        }
        if (contributed) {
            contributingSources.add("REST Client");
        }
        if (unidentified > 0) {
            warnings.add(unidentified + " outbound HTTP call" + (unidentified == 1 ? "" : "s")
                    + " could not be reduced to a safe origin and " + (unidentified == 1 ? "is" : "are")
                    + " not mapped.");
        }
    }

    // ── JDBC ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Adds configured pools as dependencies, then folds statement evidence in only where attribution is
     * unambiguous. Ambiguity is resolved by separating the evidence, never by guessing a pool.
     */
    private void collectJdbc(
            ServiceMapSources evidence,
            Map<String, NodeBuilder> dependencies,
            List<String> contributingSources,
            List<String> warnings) {
        List<HikariPoolDto> pools = evidence.jdbcPoolsAvailable() ? evidence.jdbcPools() : List.of();
        List<NodeBuilder> poolNodes = new ArrayList<>();
        for (HikariPoolDto pool : pools) {
            if (pool == null) {
                continue;
            }
            String target = ServiceMapIdentities.jdbcTarget(pool.jdbcUrl());
            String fullLabel = target != null ? target : ServiceMapIdentities.blankToNull(pool.poolName());
            if (fullLabel == null) {
                fullLabel = ServiceMapIdentities.blankToNull(pool.beanName());
            }
            if (fullLabel == null) {
                continue;
            }
            String identity = String.join(
                    "\u0000",
                    pool.beanName() == null ? "" : pool.beanName(),
                    pool.poolName() == null ? "" : pool.poolName(),
                    target == null ? "" : target);
            String id = ServiceMapIdentities.stableId("jdbc:pool:", identity);
            String detail = target == null
                    ? "Configured pool (target hidden by the value-exposure policy)"
                    : ServiceMapIdentities.blankToNull(pool.poolName());
            NodeBuilder node = new NodeBuilder(
                    id, KIND_DEPENDENCY, PROTOCOL_JDBC, ServiceMapIdentities.truncate(fullLabel), detail, true);
            node.source(BootUiPanels.DATABASE_CONNECTION_POOLS, "/database-connection-pools", "Connection Pools");
            poolNodes.add(node);
            dependencies.put(id, node);
        }
        if (!poolNodes.isEmpty()) {
            contributingSources.add("Connection Pools");
        }

        List<SqlTraceEntryDto> statements = evidence.sqlAvailable() ? evidence.sqlStatements() : List.of();
        if (statements.isEmpty()) {
            String reason = evidence.sqlAvailable()
                    ? "No statement evidence is retained for it."
                    : "SQL tracing is not contributing evidence.";
            for (NodeBuilder pool : poolNodes) {
                pool.note("Configured connection pool. " + reason);
            }
            return;
        }

        // Cardinality alone is not identity. SQL tracing can wrap a non-Hikari datasource that the pool
        // report does not discover, so the single traced name must also identify the single reported pool.
        boolean attributable = pools.size() == 1
                && poolNodes.size() == 1
                && evidence.sqlDataSourceNames().size() == 1
                && dataSourceMatchesPool(evidence.sqlDataSourceNames().get(0), pools.get(0));
        NodeBuilder target;
        if (attributable) {
            target = poolNodes.get(0);
            target.note("Configured connection pool with retained statement evidence.");
        } else {
            target = new NodeBuilder(
                    SQL_AGGREGATE_NODE_ID,
                    KIND_DEPENDENCY,
                    PROTOCOL_JDBC,
                    "SQL statements",
                    "Not attributed to a pool",
                    false);
            target.source(BootUiPanels.SQL_TRACE, "/sql-trace", "SQL Trace");
            String reason;
            if (poolNodes.isEmpty()) {
                reason = "no connection pool metadata is available";
            } else if (poolNodes.size() != 1) {
                reason = poolNodes.size() + " connection pools are configured";
            } else {
                reason = "the traced datasource cannot be matched reliably to the configured pool";
            }
            target.note("Statement evidence is summarized here because " + reason + ".");
            warnings.add("Retained SQL statements are summarized separately because " + reason
                    + ". BootUI does not attribute statements to a pool it cannot identify.");
            for (NodeBuilder pool : poolNodes) {
                pool.note("Configured connection pool. Statement evidence is reported separately.");
            }
            dependencies.put(SQL_AGGREGATE_NODE_ID, target);
        }
        for (SqlTraceEntryDto statement : statements) {
            if (statement == null) {
                continue;
            }
            // The coarse category is the only statement detail that reaches the map: never the SQL text,
            // never a bound parameter.
            target.observe(
                    "sql:" + statement.id(),
                    statement.timestamp(),
                    safeCategory(statement.category()),
                    !statement.success(),
                    statement.durationMillis(),
                    statement.traceId());
        }
        contributingSources.add("SQL Trace");
    }

    private static boolean dataSourceMatchesPool(String dataSourceName, HikariPoolDto pool) {
        String traced = ServiceMapIdentities.blankToNull(dataSourceName);
        return traced != null && (traced.equals(pool.beanName()) || traced.equals(pool.poolName()));
    }

    // ── Cache ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Folds captured cache accesses (hit/miss/put/evict/clear) into one dependency node per safe
     * cache-manager/cache-name identity — the same coarse grouping the Cache panel itself uses, never the
     * accessed key or value.
     *
     * <p>Evidence is observed-only: {@link ServiceMapSources#cacheEvents()} carries only what a
     * {@code CacheActivityRecorder} actually captured, never a declared-but-untouched cache list, so a
     * cache node is {@code configured: false} exactly like the Kafka/RabbitMQ dependencies above unless a
     * future source hands this assembler real, independent configuration evidence. A cache access has no
     * failure concept of its own — a {@code MISS} is a normal, expected outcome, not a retained failure —
     * so every cache interaction folds in as successful evidence.</p>
     */
    private void collectCache(
            ServiceMapSources evidence,
            Map<String, NodeBuilder> dependencies,
            List<String> contributingSources,
            List<String> warnings) {
        if (!evidence.cacheAvailable() || evidence.cacheEvents().isEmpty()) {
            return;
        }
        int unidentified = 0;
        boolean contributed = false;
        for (CacheActivityEvent event : evidence.cacheEvents()) {
            if (event == null) {
                continue;
            }
            String managerName = ServiceMapIdentities.blankToNull(event.managerName());
            String cacheName = ServiceMapIdentities.blankToNull(event.cacheName());
            if (cacheName == null) {
                unidentified++;
                continue;
            }
            String identity = (managerName == null ? "" : managerName) + "\u0000" + cacheName;
            String label = managerName == null ? cacheName : managerName + " / " + cacheName;
            NodeBuilder node = dependencies.computeIfAbsent(
                    ServiceMapIdentities.stableId("cache:", identity),
                    id -> new NodeBuilder(
                                    id,
                                    KIND_DEPENDENCY,
                                    PROTOCOL_CACHE,
                                    ServiceMapIdentities.truncate(label),
                                    "Cache access",
                                    false)
                            .source(BootUiPanels.CACHE, "/cache", "Cache")
                            .note("Observed cache access only. Keys and values are never mapped, only the "
                                    + "coarse hit/miss/put/evict/clear operation."));
            node.observe(
                    "cache:" + event.seq(),
                    event.timestampMillis(),
                    event.operation().name(),
                    false,
                    null,
                    event.traceId());
            contributed = true;
        }
        if (contributed) {
            contributingSources.add("Cache");
        }
        if (unidentified > 0) {
            warnings.add(unidentified + " cache access" + (unidentified == 1 ? "" : "es")
                    + " carried no cache name and " + (unidentified == 1 ? "is" : "are") + " not mapped.");
        }
    }

    // ── Messaging ────────────────────────────────────────────────────────────────────────────────

    private void collectKafka(
            ServiceMapSources evidence,
            Map<String, NodeBuilder> dependencies,
            List<String> contributingSources,
            List<String> warnings) {
        if (!evidence.kafkaAvailable() || evidence.kafkaMessages().isEmpty()) {
            return;
        }
        int unidentified = 0;
        boolean contributed = false;
        for (KafkaActivityRecorder.CapturedMessage message : evidence.kafkaMessages()) {
            // Consumed records are inbound work this application performs, not a dependency it calls out to.
            if (message == null || message.direction() != KafkaActivityRecorder.Direction.PRODUCE) {
                continue;
            }
            String topic = ServiceMapIdentities.blankToNull(message.topic());
            if (topic == null) {
                unidentified++;
                continue;
            }
            String label = ServiceMapIdentities.truncate(topic);
            NodeBuilder node = dependencies.computeIfAbsent(
                    ServiceMapIdentities.stableId("kafka:topic:", topic),
                    id -> new NodeBuilder(id, KIND_DEPENDENCY, PROTOCOL_KAFKA, label, "Produced topic", false)
                            .source(BootUiPanels.KAFKA, "/kafka", "Kafka")
                            .note("Producer evidence only. Consumed records are inbound work, not a dependency."));
            // Kafka's CapturedMessage carries no trace id at capture time, so it never joins a flow -
            // exactly as uncorrelated everywhere else messaging appears in BootUI.
            node.observe(
                    "kafka:" + message.id(),
                    message.timestamp(),
                    "PRODUCE",
                    !message.success(),
                    message.durationMillis(),
                    null);
            contributed = true;
        }
        if (contributed) {
            contributingSources.add("Kafka");
        }
        if (unidentified > 0) {
            warnings.add(unidentified + " produced Kafka record" + (unidentified == 1 ? "" : "s")
                    + " carried no topic and " + (unidentified == 1 ? "is" : "are") + " not mapped.");
        }
    }

    private void collectRabbit(
            ServiceMapSources evidence,
            Map<String, NodeBuilder> dependencies,
            List<String> contributingSources,
            List<String> warnings) {
        if (!evidence.rabbitAvailable() || evidence.rabbitMessages().isEmpty()) {
            return;
        }
        int unidentified = 0;
        boolean contributed = false;
        for (RabbitActivityRecorder.CapturedMessage message : evidence.rabbitMessages()) {
            // Consumed messages are inbound work; only publishes are an outbound dependency.
            if (message == null || message.direction() != RabbitActivityRecorder.Direction.PUBLISH) {
                continue;
            }
            String destination = ServiceMapIdentities.rabbitDestination(message.exchange(), message.routingKey());
            String identity = ServiceMapIdentities.rabbitIdentity(message.exchange(), message.routingKey());
            if (destination == null || identity == null) {
                unidentified++;
                continue;
            }
            String label = ServiceMapIdentities.truncate(destination);
            NodeBuilder node = dependencies.computeIfAbsent(
                    ServiceMapIdentities.stableId("rabbitmq:", identity),
                    id -> new NodeBuilder(id, KIND_DEPENDENCY, PROTOCOL_RABBITMQ, label, "Publish destination", false)
                            .source(BootUiPanels.RABBITMQ, "/rabbitmq", "RabbitMQ")
                            .note("Publisher evidence only. Consumed messages are inbound work, not a dependency."));
            // RabbitMQ's CapturedMessage carries no trace id at capture time either, so it never joins
            // a flow, matching Kafka above.
            node.observe(
                    "rabbitmq:" + message.id(),
                    message.timestamp(),
                    "PUBLISH",
                    !message.success(),
                    message.durationMillis(),
                    null);
            contributed = true;
        }
        if (contributed) {
            contributingSources.add("RabbitMQ");
        }
        if (unidentified > 0) {
            warnings.add(unidentified + " published AMQP message" + (unidentified == 1 ? "" : "s")
                    + " carried no exchange or routing key and " + (unidentified == 1 ? "is" : "are")
                    + " not mapped.");
        }
    }

    // ── Ranking and bounds ───────────────────────────────────────────────────────────────────────

    /**
     * Orders dependencies so truncation drops the least useful evidence first.
     *
     * <p>Configured dependencies are ranked ahead of purely observed ones: they are bounded by the
     * application's own configuration and describe the integration surface even with no traffic, so a burst
     * of one-off HTTP origins must not push a declared database off the map. Observed dependencies then rank
     * by recency, then by volume, then by label, so the order is deterministic for a given evidence set.</p>
     */
    private List<NodeBuilder> rank(Iterable<NodeBuilder> nodes) {
        List<NodeBuilder> ranked = new ArrayList<>();
        nodes.forEach(ranked::add);
        ranked.sort(Comparator.comparing((NodeBuilder node) -> node.configured ? 0 : 1)
                .thenComparing(
                        node -> node.lastSeen == null ? Long.MIN_VALUE : node.lastSeen, Comparator.reverseOrder())
                .thenComparing(node -> node.interactions, Comparator.reverseOrder())
                .thenComparing(node -> node.label));
        return ranked;
    }

    private static ServiceMapTruncationDto emptyTruncation() {
        return new ServiceMapTruncationDto(
                false, ServiceMapLimits.MAX_DEPENDENCIES, 0, 0, ServiceMapLimits.MAX_RECENT_INTERACTIONS);
    }

    private static String safeMethod(String method) {
        String value = ServiceMapIdentities.blankToNull(method);
        return value == null ? "REQUEST" : value.toUpperCase(Locale.ROOT);
    }

    private static String safeCategory(String category) {
        String value = ServiceMapIdentities.blankToNull(category);
        return value == null ? "OTHER" : value.toUpperCase(Locale.ROOT);
    }

    /**
     * Mutable accumulator for one node while evidence is folded in. Kept package-private and short-lived;
     * only its immutable {@link #toNode()} / {@link #toEdge} projections ever leave the assembler.
     */
    private static final class NodeBuilder {

        private final String id;
        private final String kind;
        private final String protocol;
        private final String label;
        private final String detail;
        private final boolean configured;
        private final Set<String> operations = new LinkedHashSet<>();
        private final List<ServiceMapInteractionDto> recent = new ArrayList<>();
        private int interactions;
        private int failures;
        private Long lastSeen;
        private String sourcePanelId;
        private String sourceRoute;
        private String sourceLabel;
        private String note;

        private NodeBuilder(String id, String kind, String protocol, String label, String detail, boolean configured) {
            this.id = id;
            this.kind = kind;
            this.protocol = protocol;
            this.label = label;
            this.detail = detail;
            this.configured = configured;
        }

        private NodeBuilder source(String panelId, String route, String label) {
            this.sourcePanelId = panelId;
            this.sourceRoute = route;
            this.sourceLabel = label;
            return this;
        }

        private NodeBuilder note(String note) {
            this.note = note;
            return this;
        }

        private void observe(String interactionId, long timestamp, String operation, boolean failed, Long durationMs) {
            observe(interactionId, timestamp, operation, failed, durationMs, null);
        }

        /**
         * Records one completed interaction, stamping it with the opaque {@code flowId} derived from
         * {@code traceId} (see {@link ServiceMapIdentities#flowId}) so the browser can recognize this
         * interaction as part of the same causal flow as others sharing that trace. {@code traceId} is
         * {@code null} for sources that never carry one (Kafka, RabbitMQ), which is why the single-argument
         * overload above exists for them.
         */
        private void observe(
                String interactionId,
                long timestamp,
                String operation,
                boolean failed,
                Long durationMs,
                String traceId) {
            interactions++;
            if (failed) {
                failures++;
            }
            if (lastSeen == null || timestamp > lastSeen) {
                lastSeen = timestamp;
            }
            operations.add(operation);
            recent.add(new ServiceMapInteractionDto(
                    interactionId,
                    timestamp,
                    operation,
                    failed ? "FAILED" : "OK",
                    durationMs,
                    ServiceMapIdentities.flowId(traceId)));
        }

        private String outcome() {
            if (interactions == 0) {
                return OUTCOME_NO_EVIDENCE;
            }
            return failures > 0 ? OUTCOME_RETAINED_FAILURES : OUTCOME_OBSERVED_OK;
        }

        private ServiceMapNodeDto toNode() {
            return new ServiceMapNodeDto(
                    id,
                    kind,
                    protocol,
                    label,
                    detail,
                    configured,
                    interactions > 0,
                    interactions,
                    failures,
                    // Only report a distinct-operation count where the source can express more than one
                    // safe operation; a single-verb protocol would otherwise always read "1" and mean nothing.
                    supportsDistinctOperations() ? operations.size() : null,
                    lastSeen,
                    outcome(),
                    sourcePanelId,
                    sourceRoute,
                    sourceLabel,
                    note);
        }

        private boolean supportsDistinctOperations() {
            return PROTOCOL_HTTP.equals(protocol)
                    || PROTOCOL_HTTP_INBOUND.equals(protocol)
                    || PROTOCOL_JDBC.equals(protocol)
                    || PROTOCOL_CACHE.equals(protocol);
        }

        private ServiceMapEdgeDto toEdge(String fromId, String toId, String direction) {
            List<ServiceMapInteractionDto> newestFirst = new ArrayList<>(recent);
            newestFirst.sort(Comparator.comparingLong(ServiceMapInteractionDto::timestamp)
                    .reversed()
                    .thenComparing(ServiceMapInteractionDto::id, Comparator.reverseOrder()));
            if (newestFirst.size() > ServiceMapLimits.MAX_RECENT_INTERACTIONS) {
                newestFirst = newestFirst.subList(0, ServiceMapLimits.MAX_RECENT_INTERACTIONS);
            }
            return new ServiceMapEdgeDto(
                    fromId + "->" + toId,
                    fromId,
                    toId,
                    protocol,
                    direction,
                    interactions,
                    failures,
                    lastSeen,
                    outcome(),
                    List.copyOf(newestFirst));
        }
    }
}
