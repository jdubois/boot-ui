package io.github.jdubois.bootui.engine.model;

import static io.github.jdubois.bootui.engine.model.JournalFixture.child;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RuntimeModelProjectionTests {

    @Test
    void executionsOwnTheirWorkByIdAndEachAccessBecomesAnObservedEdge() {
        JournalFixture journal = new JournalFixture();
        journal.request(
                "GET",
                "/api/orders/{id}",
                child(JournalSource.SQL, 2_000, sql("select * from orders o join order_line l on l.order_id = o.id")),
                child(JournalSource.SQL, 3_000, sql("select * from orders where id = ?")),
                child(JournalSource.CACHE, new CachePayload("prices", "MISS", null)),
                child(JournalSource.CACHE, new CachePayload("prices", "PUT", null)),
                child(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload("GET", "stock:8080", "/s", 200, "RestClient", false)),
                child(JournalSource.EXCEPTION, new ExceptionPayload("g1", "java.lang.IllegalStateException")));
        journal.request(
                "POST",
                "/api/orders",
                child(JournalSource.SQL, sql("insert into orders values (?)")),
                child(JournalSource.MESSAGING, new MessagingPayload("kafka", true, "orders.created", false, null)));
        journal.request("POST", "/graphql", "query Products", "trace-1");
        journal.event(
                JournalSource.AI,
                null,
                null,
                "trace-1",
                new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false));
        journal.job("ReportJob.run", child(JournalSource.SQL, sql("select count(*) from orders")));
        journal.event(
                JournalSource.MESSAGING,
                null,
                "m1",
                null,
                new MessagingPayload("kafka", false, "orders.created", false, null));
        journal.event(JournalSource.SQL, null, "m1", null, sql("update stock set n = n - 1"));
        journal.event(
                JournalSource.WEBSOCKET,
                null,
                "w1",
                null,
                WebSocketPayload.handled("stomp:/ws", "/app/chat/{room}", 12L, false));
        journal.event(JournalSource.SQL, null, "w1", null, sql("insert into chat_line values (?)"));
        // Owned by nothing: a statement on an executor that lost its context never joins a route.
        journal.event(JournalSource.SQL, null, null, null, sql("delete from audit"));

        RuntimeModel model = project(journal, null);

        Set<EdgeRef> observed = EdgeDiff.refs(model, Provenance.OBSERVED);
        String orders = "GET /api/orders/{id}";
        assertThat(observed)
                .contains(
                        ref(NodeType.ROUTE, orders, EdgeType.READS, NodeType.TABLE, "orders"),
                        ref(NodeType.ROUTE, orders, EdgeType.READS, NodeType.TABLE, "order_line"),
                        ref(NodeType.ROUTE, orders, EdgeType.READS, NodeType.CACHE, "prices"),
                        ref(NodeType.ROUTE, orders, EdgeType.WRITES, NodeType.CACHE, "prices"),
                        ref(NodeType.ROUTE, orders, EdgeType.CALLS, NodeType.HOST, "stock:8080"),
                        ref(NodeType.ROUTE, orders, EdgeType.RAISES, NodeType.EXCEPTION_GROUP, "g1"),
                        ref(NodeType.ROUTE, "POST /api/orders", EdgeType.WRITES, NodeType.TABLE, "orders"),
                        ref(
                                NodeType.ROUTE,
                                "POST /api/orders",
                                EdgeType.PUBLISHES,
                                NodeType.DESTINATION,
                                "kafka:orders.created"),
                        ref(
                                NodeType.GRAPHQL_OPERATION,
                                "POST /graphql (query Products)",
                                EdgeType.CALLS,
                                NodeType.AI_MODEL,
                                "openai:gpt-4o"),
                        ref(NodeType.SCHEDULED_JOB, "ReportJob.run", EdgeType.READS, NodeType.TABLE, "orders"),
                        ref(
                                NodeType.LISTENER,
                                "kafka:orders.created",
                                EdgeType.CONSUMES,
                                NodeType.DESTINATION,
                                "kafka:orders.created"),
                        ref(NodeType.LISTENER, "kafka:orders.created", EdgeType.WRITES, NodeType.TABLE, "stock"),
                        ref(
                                NodeType.LISTENER,
                                "websocket:/app/chat/{room}",
                                EdgeType.CONSUMES,
                                NodeType.DESTINATION,
                                "websocket:/app/chat/{room}"),
                        ref(
                                NodeType.LISTENER,
                                "websocket:/app/chat/{room}",
                                EdgeType.WRITES,
                                NodeType.TABLE,
                                "chat_line"))
                .noneMatch(edge -> edge.toKey().equals("audit"));
        ModelEdge reads = model
                .outgoing(model.node(NodeType.ROUTE, orders).orElseThrow().id())
                .stream()
                .filter(edge -> edge.type() == EdgeType.READS
                        && model.node(edge.to()).key().equals("orders"))
                .findFirst()
                .orElseThrow();
        assertThat(reads.count()).isEqualTo(2);
        assertThat(reads.firstSeenEpochMillis()).isEqualTo(2_000);
        assertThat(reads.lastSeenEpochMillis()).isEqualTo(3_000);
        assertThat(model.executions(
                        model.node(NodeType.ROUTE, orders).orElseThrow().id()))
                .isEqualTo(1);
        assertThat(model.partial()).isFalse();
    }

    @Test
    void declaredStructureIsKeptApartFromObservedExecution() {
        JournalFixture journal = new JournalFixture();
        journal.request("GET", "/api/orders/{id}", child(JournalSource.SQL, sql("select * from orders")));
        StructureSnapshot structure = new StructureSnapshot(
                "run-1",
                List.of(new StructureSnapshot.RouteHandler("GET /api/orders/{id}", "com.example.OrderController")),
                List.of(
                        new StructureSnapshot.Bean(
                                "orderController", "com.example.OrderController", false, List.of("orderRepository")),
                        new StructureSnapshot.Bean("orderRepository", "com.example.OrderRepository", true, List.of())));

        RuntimeModel model = project(journal, structure);

        assertThat(EdgeDiff.refs(model, Provenance.DECLARED))
                .containsExactlyInAnyOrder(
                        ref(
                                NodeType.BEAN,
                                "orderController",
                                EdgeType.DEPENDS_ON,
                                NodeType.REPOSITORY,
                                "orderRepository"),
                        ref(
                                NodeType.ROUTE,
                                "GET /api/orders/{id}",
                                EdgeType.HANDLED_BY,
                                NodeType.BEAN,
                                "orderController"));
        assertThat(EdgeDiff.refs(model, Provenance.OBSERVED))
                .containsExactly(ref(NodeType.ROUTE, "GET /api/orders/{id}", EdgeType.READS, NodeType.TABLE, "orders"));
        assertThat(model.runId()).isEqualTo("run-1");
        assertThat(model.edges())
                .filteredOn(edge -> edge.provenance() == Provenance.DECLARED)
                .allSatisfy(edge -> assertThat(edge.count()).isZero());
    }

    @Test
    void twoRoutesThatShareOnlyATableNeverReachEachOther() {
        JournalFixture journal = new JournalFixture();
        journal.request("GET", "/api/orders", child(JournalSource.SQL, sql("select * from orders")));
        journal.request("POST", "/api/orders", child(JournalSource.SQL, sql("insert into orders values (?)")));
        RuntimeModel model = project(journal, null);
        int reader = model.node(NodeType.ROUTE, "GET /api/orders").orElseThrow().id();
        int writer =
                model.node(NodeType.ROUTE, "POST /api/orders").orElseThrow().id();
        int table = model.node(NodeType.TABLE, "orders").orElseThrow().id();
        Set<EdgeType> every = Set.of(EdgeType.values());

        assertThat(ReverseClosure.of(model, table, every, 5)).containsOnlyKeys(reader, writer);
        assertThat(ReverseClosure.of(model, reader, every, 5)).isEmpty();
        assertThat(ReverseClosure.of(model, writer, every, 5)).isEmpty();
    }

    @Test
    void capsAndTheReadBudgetMakeTheModelPartialWithTheReason() {
        JournalFixture journal = new JournalFixture();
        for (int i = 0; i < 3_000; i++) {
            journal.request("GET", "/api/r" + i, child(JournalSource.SQL, sql("select * from t" + i)));
        }
        AtomicLong now = new AtomicLong();
        RuntimeModel stopped = RuntimeModelProjection.project(
                journal.entries(),
                RouteTemplateResolver.empty(),
                null,
                7,
                () -> now.addAndGet(50_000_000),
                100_000_000);

        assertThat(stopped.partial()).isTrue();
        assertThat(stopped.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("100 ms read budget"))
                .anySatisfy(limitation -> assertThat(limitation)
                        .contains("edges of 0 of " + journal.entries().size() + " retained events"))
                .anySatisfy(limitation -> assertThat(limitation).contains("evicted 7"))
                .noneSatisfy(limitation -> assertThat(limitation).contains("No retained event belongs"));

        RuntimeModel capped = project(journal, null);
        assertThat(capped.nodes()).hasSize(RuntimeModel.MAX_NODES);
        assertThat(capped.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("5000-node cap"));
    }

    @Test
    void aBudgetReachedWhileReadingEdgesNamesHowManyEventsTheEdgesCover() {
        JournalFixture journal = new JournalFixture();
        for (int i = 0; i < 3_000; i++) {
            journal.request("GET", "/api/r" + (i % 10), child(JournalSource.SQL, sql("select * from t" + (i % 10))));
        }
        int entries = journal.entries().size();
        // The start, one reading per 1,024 events of the first pass, then two of the second pass stay in budget.
        int inBudget = 1 + entries / 1_024 + 2;
        AtomicLong readings = new AtomicLong();
        RuntimeModel stopped = RuntimeModelProjection.project(
                journal.entries(),
                RouteTemplateResolver.empty(),
                null,
                0,
                () -> readings.incrementAndGet() <= inBudget ? 0 : 1_000_000_000,
                100_000_000);

        assertThat(stopped.partial()).isTrue();
        assertThat(stopped.edges()).isNotEmpty();
        assertThat(stopped.limitations())
                .containsExactly("The projection stopped at its 100 ms read budget after reading the edges of 2048 of "
                        + entries + " retained events.");
    }

    static RuntimeModel project(JournalFixture journal, StructureSnapshot structure) {
        return RuntimeModelProjection.project(
                journal.entries(), RouteTemplateResolver.empty(), structure, 0, System::nanoTime, Long.MAX_VALUE);
    }

    static SqlPayload sql(String sql) {
        return new SqlPayload(sql, null, "db", false);
    }

    static EdgeRef ref(NodeType fromType, String from, EdgeType type, NodeType toType, String to) {
        return new EdgeRef(fromType, from, type, toType, to);
    }
}
