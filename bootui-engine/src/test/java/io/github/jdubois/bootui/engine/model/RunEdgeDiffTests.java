package io.github.jdubois.bootui.engine.model;

import static io.github.jdubois.bootui.engine.model.JournalFixture.child;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RunEdgeDiffTests {

    private static final EdgeRef ORDERS_READ =
            new EdgeRef(NodeType.ROUTE, "GET /api/orders", EdgeType.READS, NodeType.TABLE, "orders");

    private static final EdgeRef PAY_CALL =
            new EdgeRef(NodeType.ROUTE, "GET /api/orders", EdgeType.CALLS, NodeType.HOST, "pay.internal:8443");

    private static final EdgeRef PRICES_READ =
            new EdgeRef(NodeType.ROUTE, "GET /api/orders", EdgeType.READS, NodeType.CACHE, "prices");

    @Test
    void theAggregatesCountTheEdgesTheModelProjectsWhateverOrderTheirEventsArriveIn() {
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
        // Exported after its request, joined by trace id.
        journal.event(
                JournalSource.AI,
                null,
                null,
                "trace-1",
                new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false));
        // Children first, completed by the job's event.
        journal.job("ReportJob.run", child(JournalSource.SQL, sql("select count(*) from orders")));
        // A received message, then a statement of its listener after it.
        journal.event(
                JournalSource.MESSAGING,
                null,
                "m1",
                null,
                new MessagingPayload("kafka", false, "orders.created", false, null));
        journal.event(JournalSource.SQL, null, "m1", null, sql("update stock set n = n - 1"));
        journal.event(JournalSource.SQL, null, null, null, sql("delete from audit"));
        JournalAggregates aggregates = new JournalAggregates();

        aggregates.onEntries(journal.entries());
        RuntimeModel model = RuntimeModelProjection.project(
                journal.entries(),
                RouteTemplateResolver.empty(),
                null,
                0,
                System::nanoTime,
                RuntimeModelProjection.READ_BUDGET_NANOS);

        Map<EdgeRef, Long> counted = counts(aggregates.snapshot().edges());
        Map<EdgeRef, Long> projected = new HashMap<>();
        for (ModelEdge edge : model.edges()) {
            if (edge.provenance() == Provenance.OBSERVED) {
                ModelNode from = model.node(edge.from());
                ModelNode to = model.node(edge.to());
                projected.put(new EdgeRef(from.type(), from.key(), edge.type(), to.type(), to.key()), edge.count());
            }
        }
        assertThat(counted).isEqualTo(projected).hasSize(12);
        assertThat(counted)
                .containsEntry(
                        new EdgeRef(
                                NodeType.GRAPHQL_OPERATION,
                                "POST /graphql (query Products)",
                                EdgeType.CALLS,
                                NodeType.AI_MODEL,
                                "openai:gpt-4o"),
                        1L)
                .containsEntry(
                        new EdgeRef(
                                NodeType.LISTENER, "kafka:orders.created", EdgeType.WRITES, NodeType.TABLE, "stock"),
                        1L)
                .doesNotContainKey(new EdgeRef(
                        NodeType.LISTENER, "kafka:orders.created", EdgeType.WRITES, NodeType.TABLE, "audit"));
        assertThat(aggregates.snapshot().edges())
                .filteredOn(edge -> edge.edge().toKey().equals("orders")
                        && edge.edge().fromKey().equals("GET /api/orders/{id}"))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.count()).isEqualTo(2);
                    assertThat(edge.firstSeenEpochMillis()).isEqualTo(2_000);
                    assertThat(edge.lastSeenEpochMillis()).isEqualTo(3_000);
                });
    }

    @Test
    void anEdgeWhoseEventsTheJournalLaterEvictsStillCounts() {
        JournalFixture journal = new JournalFixture();
        for (int i = 0; i < 20; i++) {
            journal.request("GET", "/api/orders", child(JournalSource.SQL, sql("select * from orders")));
        }
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(journal.entries());
        List<JournalEntry> retained = journal.entries()
                .subList(journal.entries().size() - 4, journal.entries().size());

        RuntimeModel model = RuntimeModelProjection.project(
                retained, RouteTemplateResolver.empty(), null, 36, System::nanoTime, 1_000_000_000L);

        assertThat(counts(aggregates.snapshot().edges())).containsEntry(ORDERS_READ, 20L);
        assertThat(model.edges())
                .filteredOn(edge -> edge.provenance() == Provenance.OBSERVED)
                .singleElement()
                .satisfies(edge -> assertThat(edge.count()).isEqualTo(2));
    }

    @Test
    void edgesBeyondTheirCapsAreCountedAsOverflowAndClearingDropsThem() {
        JournalFixture journal = new JournalFixture();
        JournalFixture.Child[] tables = new JournalFixture.Child[JournalAggregates.MAX_EDGES + 10];
        for (int i = 0; i < tables.length; i++) {
            tables[i] = child(JournalSource.SQL, sql("select * from table_" + i));
        }
        // 64 tables per request, as an execution holds at most that many edges before it completes.
        for (int start = 0; start < tables.length; start += 64) {
            journal.request(
                    "GET",
                    "/api/report/" + start,
                    java.util.Arrays.copyOfRange(tables, start, Math.min(tables.length, start + 64)));
        }
        journal.request("GET", "/api/wide", java.util.Arrays.copyOfRange(tables, 0, 70));
        JournalAggregates aggregates = new JournalAggregates();

        aggregates.onEntries(journal.entries());

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.edges()).hasSize(JournalAggregates.MAX_EDGES);
        assertThat(snapshot.overflowed().get(JournalAggregates.EDGES))
                .as("10 edges beyond the cap, and the wide request's 6 beyond its own and 64 beyond the cap")
                .isEqualTo(10 + 6 + 64);

        aggregates.clear();

        assertThat(aggregates.snapshot().edges()).isEmpty();
        assertThat(aggregates.snapshot().overflowed()).containsEntry(JournalAggregates.EDGES, 0L);
    }

    @Test
    void theComparisonReportsTheEdgesARunAddedAndRemovedWithTheirCounts() {
        RunSummary previous = summary(1, 0, run(journal -> {
            for (int i = 0; i < 4; i++) {
                journal.request(
                        "GET",
                        "/api/orders",
                        child(JournalSource.SQL, sql("select * from orders")),
                        child(JournalSource.CACHE, new CachePayload("prices", "GET", null)));
            }
        }));
        AggregatesSnapshot current = run(journal -> {
            for (int i = 0; i < 15; i++) {
                journal.request(
                        "GET",
                        "/api/orders",
                        child(JournalSource.SQL, sql("select * from orders")),
                        child(
                                JournalSource.REST_CLIENT,
                                new RestClientPayload("POST", "pay.internal:8443", "/pay", 200, "RestClient", false)));
            }
        });

        RunEdgeDiff diff = RunEdgeDiff.compare(previous, current, null);

        assertThat(diff.previousRunId()).isEqualTo(previous.header().runId());
        assertThat(diff.added()).singleElement().satisfies(edge -> {
            assertThat(edge.edge()).isEqualTo(PAY_CALL);
            assertThat(edge.count()).isEqualTo(15);
        });
        assertThat(diff.removed()).singleElement().satisfies(edge -> {
            assertThat(edge.edge()).isEqualTo(PRICES_READ);
            assertThat(edge.count()).isEqualTo(4);
        });
        assertThat(diff.limitations()).isEmpty();
    }

    @Test
    void theComparisonSaysWhenThereIsNoPreviousRunOrItKeptNoEdgesOrLeftSomeOut() {
        AggregatesSnapshot current = run(journal ->
                journal.request("GET", "/api/orders", child(JournalSource.SQL, sql("select * from orders"))));

        assertThat(RunEdgeDiff.compare(null, current, null).limitations())
                .containsExactly("No previous run is kept, so no edge is compared.");
        assertThat(RunEdgeDiff.compare(null, current, "BootUI is loaded by the restart class loader.")
                        .limitations())
                .containsExactly("BootUI is loaded by the restart class loader.");

        RunSummary withoutEdges = summary(2, 0, run(journal -> journal.request("GET", "/api/health")));
        RunEdgeDiff fromNoEdges = RunEdgeDiff.compare(withoutEdges, current, null);
        assertThat(fromNoEdges.added()).extracting(ObservedEdge::edge).containsExactly(ORDERS_READ);
        assertThat(fromNoEdges.limitations())
                .containsExactly("Run 2 kept no edges, so every edge of this run is reported as added.");

        RunSummary trimmed = summary(3, 7, current);
        assertThat(RunEdgeDiff.compare(trimmed, current, null).limitations())
                .singleElement()
                .asString()
                .contains("Run 3's summary left out its 7 least-observed edges")
                .contains("may have been among them");
    }

    private static AggregatesSnapshot run(java.util.function.Consumer<JournalFixture> recorder) {
        JournalFixture journal = new JournalFixture();
        recorder.accept(journal);
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(journal.entries());
        return aggregates.snapshot();
    }

    private static RunSummary summary(int ordinal, int omittedEdges, AggregatesSnapshot aggregates) {
        RunSummary of = RunSummary.of(RunIdentity.start(), aggregates, 1);
        RunSummary.Header header = of.header();
        return new RunSummary(
                new RunSummary.Header(
                        header.runId(),
                        ordinal,
                        header.startedAtEpochMillis(),
                        header.endedAtEpochMillis(),
                        header.requests(),
                        header.failedRequests(),
                        header.events(),
                        omittedEdges,
                        omittedEdges,
                        0),
                aggregates);
    }

    private static Map<EdgeRef, Long> counts(List<ObservedEdge> edges) {
        return edges.stream().collect(Collectors.toMap(ObservedEdge::edge, ObservedEdge::count));
    }

    private static SqlPayload sql(String sql) {
        return RuntimeModelProjectionTests.sql(sql);
    }
}
