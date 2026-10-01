package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JournalAggregatesTests {

    private final JournalAggregates aggregates = new JournalAggregates();
    private long sequence;

    @Test
    void aRequestsChildrenFoldIntoItsRouteWhenItCompletes() {
        publish(sql("r1", "select * from orders where id = ?", 2_000_000, "OrderRepository.find:42", false));
        publish(sql("r1", "select * from orders where id = ?", 1_000_000, "OrderRepository.find:42", false));
        publish(sql("r1", "select * from lines where order_id = ?", 3_000_000, "LineRepository.find:17", true));
        publish(event("r1", JournalSource.REST_CLIENT, 4_000_000, null));
        publish(event(
                "r1", JournalSource.EXCEPTION, -1, new ExceptionPayload("g1", "java.lang.IllegalStateException")));
        publish(event("r1", JournalSource.CONNECTION, 9_000_000, new ConnectionPayload("orders", 2_000_000, 3)));
        publish(http("r1", "/api/orders/{id}", 500, 20_000_000));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        RouteStats route = snapshot.routes().get(0);
        assertThat(route.route()).isEqualTo("GET /api/orders/{id}");
        assertThat(route.requests()).isEqualTo(1);
        assertThat(route.statusClasses()).containsExactly(0L, 0L, 0L, 0L, 1L);
        assertThat(route.latency().percentileMicros(50)).isBetween(18_750L, 21_250L);
        assertThat(route.childCounts())
                .containsEntry(JournalSource.SQL, 3L)
                .containsEntry(JournalSource.REST_CLIENT, 1L)
                .containsEntry(JournalSource.EXCEPTION, 1L);
        assertThat(route.childNanos())
                .containsEntry(JournalSource.SQL, 6_000_000L)
                .containsEntry(JournalSource.REST_CLIENT, 4_000_000L)
                .containsEntry(JournalSource.CONNECTION, 9_000_000L);
        assertThat(route.connectionWaitNanos()).isEqualTo(2_000_000L);
        assertThat(route.statements())
                .containsEntry("select * from orders where id = ?", 2L)
                .containsEntry("select * from lines where order_id = ?", 1L);
        assertThat(snapshot.statements())
                .filteredOn(statement -> statement.fingerprint().startsWith("select * from lines"))
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.executions()).isEqualTo(1);
                    assertThat(statement.failures()).isEqualTo(1);
                    assertThat(statement.callSites()).containsEntry("LineRepository.find:17", 1L);
                });
        assertThat(snapshot.exceptionGroups()).singleElement().satisfies(group -> {
            assertThat(group.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
            assertThat(group.routes()).containsEntry("GET /api/orders/{id}", 1L);
        });
        assertThat(snapshot.run().requests()).isEqualTo(1);
        assertThat(snapshot.run().failedRequests()).isEqualTo(1);
        assertThat(snapshot.run().openRequests()).isZero();
    }

    @Test
    void aRouteIsNamedByItsTemplateThenTheDeclaredRoutesThenItsMaskedPath() {
        aggregates.setDeclaredRoutes(() -> RouteTemplateResolver.of(
                List.of(new MappingDto("GET", "/api/customers/{customerId}", "h", null, null))));
        publish(httpEvent("r1", new HttpPayload("GET", "/api/orders/42", "/api/orders/{id}", null, 200)));
        publish(httpEvent("r2", new HttpPayload("GET", "/api/customers/7", null, null, 200)));
        publish(httpEvent("r3", new HttpPayload("GET", "/api/files/123456", null, null, 200)));
        publish(httpEvent("r4", new HttpPayload("POST", "/graphql", "/graphql", "query ProductList", 200)));

        assertThat(aggregates.snapshot().routes())
                .extracting(RouteStats::route)
                .containsExactly(
                        "GET /api/orders/{id}",
                        "GET /api/customers/{customerId}",
                        "GET /api/files/{value}",
                        "POST /graphql (query ProductList)");
    }

    @Test
    void statementsAggregateByTheirLiteralFreeFingerprint() {
        publish(sql("r1", "SELECT * FROM orders WHERE id = 41", 1_000, null, false));
        publish(sql("r1", "select * from orders where id = 42", 1_000, null, false));

        assertThat(aggregates.snapshot().statements()).singleElement().satisfies(statement -> {
            assertThat(statement.fingerprint()).isEqualTo("select * from orders where id = ?");
            assertThat(statement.executions()).isEqualTo(2);
        });
    }

    @Test
    void workOutsideAnyRequestOrExecutionIsCountedPerThreadFamily() {
        publish(onThread(null, "pool-3-thread-7", 1_000));
        publish(onThread(null, "pool-3-thread-12", 2_000));
        publish(onThread("r1", "http-nio-8080-exec-1", 5_000));
        publish(new RuntimeEvent(
                JournalSource.SQL, 1, 7_000, null, "job-1", null, null, "scheduling-1", null, false, null));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.threadFamilies()).singleElement().satisfies(family -> {
            assertThat(family.family()).isEqualTo("pool-N-thread-N");
            assertThat(family.events()).containsEntry(JournalSource.SQL, 2L);
            assertThat(family.nanos()).containsEntry(JournalSource.SQL, 3_000L);
        });
        assertThat(ThreadFamilies.of(null)).isEqualTo(ThreadFamilies.UNKNOWN);
        assertThat(ThreadFamilies.of("reactor-http-nio-12")).isEqualTo("reactor-http-nio-N");
    }

    @Test
    void everyDimensionIsCappedWithAVisibleOtherBucket() {
        for (int i = 0; i < JournalAggregates.MAX_ROUTES + 5; i++) {
            publish(http("r" + i, "/api/items/" + i, 200, 1_000));
        }

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.routes()).hasSize(JournalAggregates.MAX_ROUTES + 1);
        assertThat(snapshot.routes().get(JournalAggregates.MAX_ROUTES).route()).isEqualTo(CappedMap.OTHER);
        assertThat(snapshot.routes().get(JournalAggregates.MAX_ROUTES).requests())
                .isEqualTo(5);
        assertThat(snapshot.overflowed()).containsEntry("routes", 5L);
        assertThat(snapshot.routes().stream().mapToLong(RouteStats::requests).sum())
                .isEqualTo(JournalAggregates.MAX_ROUTES + 5);
    }

    @Test
    void theOldestOpenRequestIsDroppedAndCountedBeyondTheBound() {
        for (int i = 0; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(sql("r" + i, "select 1", 1_000, null, false));
        }
        publish(http("r0", "/api/first", 200, 1_000));
        publish(http("r1", "/api/second", 200, 1_000));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.run().unattributedRequests()).isEqualTo(1);
        assertThat(snapshot.routes())
                .filteredOn(route -> route.route().equals("GET /api/first"))
                .singleElement()
                .satisfies(route -> assertThat(route.childCounts()).isEmpty());
        assertThat(snapshot.routes())
                .filteredOn(route -> route.route().equals("GET /api/second"))
                .singleElement()
                .satisfies(route -> assertThat(route.childCounts()).containsEntry(JournalSource.SQL, 1L));
    }

    @Test
    void aggregatesReconcileWithEveryPublishedEventIncludingThoseTheJournalEvicted() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 10_000_000, 100_000, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        JournalAggregates listened = new JournalAggregates();
        journal.addListener(listened);
        Random random = new Random(7);
        long requests = 0;
        long statements = 0;
        long exceptions = 0;
        for (int request = 0; request < 2_000; request++) {
            String requestId = "r" + request;
            int queries = random.nextInt(4);
            for (int q = 0; q < queries; q++) {
                journal.offer(sql(requestId, "select " + random.nextInt(30), random.nextInt(5_000_000), null, false));
                statements++;
            }
            if (random.nextInt(10) == 0) {
                journal.offer(event(requestId, JournalSource.EXCEPTION, -1, new ExceptionPayload("g", "E")));
                exceptions++;
            }
            journal.offer(http(requestId, "/api/r" + random.nextInt(20), 200, random.nextInt(50_000_000)));
            requests++;
        }
        journal.dispatchPending();

        AggregatesSnapshot snapshot = listened.snapshot();
        assertThat(journal.status().retainedEvents())
                .as("the journal evicted most events")
                .isEqualTo(100);
        assertThat(snapshot.routes().stream().mapToLong(RouteStats::requests).sum())
                .isEqualTo(requests);
        assertThat(snapshot.statements().stream()
                        .mapToLong(JournalAggregates.StatementStats::executions)
                        .sum())
                .isEqualTo(statements);
        assertThat(snapshot.routes().stream()
                        .mapToLong(route -> route.childCounts().getOrDefault(JournalSource.SQL, 0L))
                        .sum())
                .isEqualTo(statements);
        assertThat(snapshot.exceptionGroups().get(0).occurrences()).isEqualTo(exceptions);
        assertThat(snapshot.run().events())
                .isEqualTo(Map.of(
                        JournalSource.HTTP,
                        requests,
                        JournalSource.SQL,
                        statements,
                        JournalSource.EXCEPTION,
                        exceptions));
        assertThat(snapshot.run().unattributedRequests()).isZero();
        assertThat(snapshot.run().openRequests()).isZero();
    }

    @Test
    void clearingDropsEveryAggregate() {
        publish(http("r1", "/api/orders", 200, 1_000));

        aggregates.clear();

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.routes()).isEmpty();
        assertThat(snapshot.run().events()).isEmpty();
        assertThat(snapshot.run().firstEpochMillis()).isNull();
    }

    private void publish(RuntimeEvent event) {
        List<JournalEntry> batch = new ArrayList<>();
        batch.add(new JournalEntry(++sequence, event, event.estimatedBytes()));
        aggregates.onEntries(batch);
    }

    private static RuntimeEvent http(String requestId, String route, int status, long nanos) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                nanos,
                CorrelationContext.forRequest(requestId),
                "http-nio-8080-exec-1",
                null,
                status >= 500,
                new HttpPayload("GET", route, route, null, status));
    }

    private static RuntimeEvent httpEvent(String requestId, HttpPayload payload) {
        return RuntimeEvent.of(
                JournalSource.HTTP, 1_000, 1_000, CorrelationContext.forRequest(requestId), "t", null, false, payload);
    }

    private static RuntimeEvent sql(String requestId, String fingerprint, long nanos, String callSite, boolean failed) {
        return event(requestId, JournalSource.SQL, nanos, new SqlPayload(fingerprint, callSite, "dataSource", failed));
    }

    private static RuntimeEvent event(String requestId, JournalSource source, long nanos, RuntimeEventPayload payload) {
        return RuntimeEvent.of(
                source,
                1_000,
                nanos,
                CorrelationContext.forRequest(requestId),
                "http-nio-8080-exec-1",
                null,
                false,
                payload);
    }

    private static RuntimeEvent onThread(String requestId, String thread, long nanos) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                nanos,
                requestId == null ? CorrelationContext.NONE : CorrelationContext.forRequest(requestId),
                thread,
                null,
                false,
                new SqlPayload("select 1", null, null, false));
    }
}
