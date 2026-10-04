package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteResources;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ThreadFamilyStats;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
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
    void aFailedWebSocketHandlerRecordsAnExecutionFailure() {
        publish(execution("ws", JournalSource.WEBSOCKET, WebSocketPayload.handled("/chat", "/room", 10L, true)));
        assertThat(aggregates.snapshot().executions()).singleElement().satisfies(work -> {
            assertThat(work.source()).isEqualTo(JournalSource.WEBSOCKET);
            assertThat(work.stats().requests()).isEqualTo(1);
            assertThat(work.stats().statusClasses()).containsExactly(0L, 0L, 0L, 0L, 1L);
        });
    }

    @Test
    void anExecutionWithoutDurationStillCountsItsCompletedWork() {
        publish(RuntimeEvent.of(
                JournalSource.MESSAGING,
                1000,
                -1,
                CorrelationContext.forExecution("message"),
                "worker",
                null,
                false,
                new MessagingPayload("rabbitmq", false, "orders", false)));
        assertThat(aggregates.snapshot().executions()).singleElement().satisfies(work -> {
            assertThat(work.stats().requests()).isEqualTo(1);
            assertThat(work.stats().latency().count()).isZero();
        });
    }

    @Test
    void unfinishedExecutionWorkCannotEvictAnInflightRequestsChildren() {
        publish(sql("request", "select * from orders", 1, null, false));
        for (int i = 0; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(execution("job-" + i, JournalSource.SQL, new SqlPayload("select 1", null, "db", false)));
        }
        publish(http("request", "/orders", 200, 1));
        assertThat(aggregates.snapshot().routes())
                .singleElement()
                .satisfies(route -> assertThat(route.statements()).containsEntry("select * from orders", 1L));
        assertThat(aggregates.snapshot().run().unattributedRequests()).isZero();
        assertThat(aggregates.snapshot().overflowed()).containsEntry("unattributedExecutions", 1L);
    }

    @Test
    void completedExecutionIgnoresLateChildrenInCountersButKeepsTheirEdges() {
        publish(execution("job", JournalSource.SQL, new SqlPayload("select * from orders", null, "db", false)));
        publish(execution("job", JournalSource.SCHEDULED, new ScheduledPayload("Job.run", null)));
        publish(execution("job", JournalSource.SQL, new SqlPayload("select * from lines", null, "db", false)));
        assertThat(aggregates.snapshot().executions())
                .singleElement()
                .satisfies(work -> assertThat(work.stats().statements()).containsOnlyKeys("select * from orders"));
        assertThat(aggregates.snapshot().edges())
                .extracting(edge -> edge.edge().toKey())
                .contains("orders", "lines");
        aggregates.clear();
        assertThat(aggregates.snapshot().executions()).isEmpty();
    }

    private static RuntimeEvent execution(String id, JournalSource source, RuntimeEventPayload payload) {
        return RuntimeEvent.of(
                source, 1000, 1_000_000, CorrelationContext.forExecution(id), "worker", null, false, payload);
    }

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
        publish(new RuntimeEvent(JournalSource.SQL, 1, 7_000, null, "job-1", null, "scheduling-1", null, false, null));

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
    void eventsNotObservedOnAThreadBelongToNoThreadFamily() {
        publish(new RuntimeEvent(
                JournalSource.AI,
                1,
                45_000_000,
                null,
                null,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                null,
                null,
                false,
                new AiPayload("chat", "openai", "gpt-4o", 1200L, 300L, "stop", false)));
        publish(new RuntimeEvent(
                JournalSource.MESSAGING,
                1,
                -1,
                null,
                null,
                null,
                null,
                null,
                false,
                new MessagingPayload("kafka", true, "orders", false)));
        publish(onThread(null, "pool-3-thread-7", 1_000));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.threadFamilies())
                .extracting(ThreadFamilyStats::family)
                .containsExactly("pool-N-thread-N");
        assertThat(snapshot.threadFamilies())
                .extracting(ThreadFamilyStats::family)
                .doesNotContain(ThreadFamilies.UNKNOWN);
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
    void aRoutesResourcesSumOnlyItsFullyMeasuredRequestsAndCountTheOthers() {
        publish(httpEvent("r1", resourced(new ResourceUsage(4_000_000, 1_000, 2, 0, null, 0, List.of(), false))));
        publish(httpEvent("r2", resourced(new ResourceUsage(6_000_000, 3_000, 1, 0, null, 0, List.of(), false))));
        publish(httpEvent(
                "r3",
                resourced(new ResourceUsage(
                        1_000_000, 500, 2, 1, ResourceUsage.Unmeasured.VIRTUAL_THREAD, 0, List.of(), false))));
        publish(httpEvent(
                "r4",
                resourced(
                        new ResourceUsage(0, 0, 1, 1, ResourceUsage.Unmeasured.VIRTUAL_THREAD, 0, List.of(), false))));
        publish(httpEvent("r5", new HttpPayload("GET", "/api/orders", "/api/orders", null, 200)));

        RouteResources resources = aggregates.snapshot().routes().get(0).resources();

        assertThat(resources.measuredRequests()).isEqualTo(2);
        assertThat(resources.cpuNanos()).isEqualTo(10_000_000);
        assertThat(resources.allocatedBytes()).isEqualTo(4_000);
        assertThat(resources.partialRequests()).isEqualTo(1);
        assertThat(resources.unmeasuredRequests()).isEqualTo(1);
    }

    @Test
    void gcPausesJoinTheirRequestsByIdWhetherTheirEventArrivesBeforeOrAfterTheRequest() {
        publish(gc("G1 Young Generation", 41, 7));
        publish(httpEvent(
                "r1",
                resourced(new ResourceUsage(
                        1, 1, 1, 0, null, 2, List.of(new GcPauseRange("G1 Young Generation", 40, 42)), false))));
        publish(gc("G1 Young Generation", 42, 5));
        publish(gc("G1 Young Generation", 43, 100));
        publish(gc("ZGC Major Cycles", 7, 900));

        RouteResources resources = aggregates.snapshot().routes().get(0).resources();

        assertThat(resources.gcPauses()).isEqualTo(2);
        assertThat(resources.requestsWithGcPause()).isEqualTo(1);
        assertThat(resources.gcPauseNanos())
                .as("collections 41 (before) and 42 (after), never 43 or a concurrent cycle")
                .isEqualTo(12_000_000);
        assertThat(aggregates.snapshot().threadFamilies())
                .as("a collection is no thread family's work")
                .isEmpty();
        assertThat(aggregates.snapshot().run().events()).containsEntry(JournalSource.GC, 4L);
    }

    @Test
    void aRoutesRequestsAreCountedByHowTheirCallerWasAuthenticatedAndWhetherAnyDecisionDenied() {
        publish(event("r1", JournalSource.AUTHORIZATION, 1_000, decision("REQUEST", "ANONYMOUS", true)));
        publish(http("r1", "/api/products", 200, 1_000_000));
        publish(event("r2", JournalSource.AUTHORIZATION, 1_000, decision("METHOD", "AUTHENTICATED", true)));
        publish(event("r2", JournalSource.AUTHORIZATION, 1_000, decision("REQUEST", "ANONYMOUS", false)));
        publish(http("r2", "/api/products", 401, 1_000_000));
        publish(event("r3", JournalSource.AUTHORIZATION, 1_000, decision("REQUEST", "AUTHENTICATED", true)));
        publish(http("r3", "/api/products", 200, 1_000_000));
        publish(http("r4", "/api/products", 200, 1_000_000));

        JournalAggregates.RouteAuthorization authorization =
                aggregates.snapshot().routes().get(0).authorization();
        assertThat(authorization).isEqualTo(new JournalAggregates.RouteAuthorization(2, 1, 0, 0, 1, 1));
        assertThat(authorization.decided()).isEqualTo(3);
    }

    private static AuthorizationPayload decision(String target, String authentication, boolean granted) {
        return new AuthorizationPayload(target, null, null, authentication, granted, 0);
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

    @Test
    void aChildRecordedAfterItsRequestCompletedOpensNoPendingRequest() {
        for (int i = 0; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(http("r" + i, "/api/chat", 200, 1_000_000));
            // An AI call exported in a later batch than its request.
            publish(event(
                    "r" + i,
                    JournalSource.AI,
                    1_000,
                    new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", 1L, 1L, "stop", false)));
        }

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.run().openRequests()).isZero();
        assertThat(snapshot.run().unattributedRequests())
                .as("no completed request is evicted as if its HTTP event never arrived")
                .isZero();
    }

    @Test
    void exactlyOwnedLateChildrenStillFoldIntoTheirCompletedRoute() {
        publish(http("r1", "/api/chat", 200, 1_000_000));
        publish(event(
                "r1",
                JournalSource.AI,
                5_000_000,
                new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", 11L, 22L, "stop", false)));
        publish(sql("r1", "select * from messages where id = ?", 2_000_000, null, false));
        publish(event(
                "r1", JournalSource.EXCEPTION, -1, new ExceptionPayload("g1", "java.lang.IllegalStateException")));
        publish(event(
                "r1", JournalSource.EXCEPTION, -1, new ExceptionPayload("g1", "java.lang.IllegalStateException")));
        publish(event("r1", JournalSource.CONNECTION, 9_000_000, new ConnectionPayload("main", 3_000_000, 1)));

        AggregatesSnapshot snapshot = aggregates.snapshot();
        RouteStats route = snapshot.routes().get(0);

        assertThat(route.requests()).isEqualTo(1);
        assertThat(route.childCounts())
                .containsEntry(JournalSource.AI, 1L)
                .containsEntry(JournalSource.SQL, 1L)
                .containsEntry(JournalSource.EXCEPTION, 2L)
                .containsEntry(JournalSource.CONNECTION, 1L);
        assertThat(route.aiTokens()).isEqualTo(33);
        assertThat(route.connectionWaitNanos()).isEqualTo(3_000_000);
        assertThat(route.statements()).containsEntry("select * from messages where id = ?", 1L);
        assertThat(snapshot.exceptionGroups())
                .singleElement()
                .satisfies(group -> assertThat(group.routes()).containsEntry("GET /api/chat", 1L));
        assertThat(snapshot.run().openRequests()).isZero();
    }

    @Test
    void lateOncePerRequestFactsReclassifyInsteadOfCountingTheRequestTwice() {
        publish(http("r1", "/api/orders", 200, 1_000_000));
        publish(event("r1", JournalSource.AUTHORIZATION, 1, decision("METHOD", "ANONYMOUS", true)));
        publish(event("r1", JournalSource.AUTHORIZATION, 1, decision("REQUEST", "AUTHENTICATED", false)));
        publish(event(
                "r1",
                JournalSource.ORM,
                4_000_000,
                new OrmPayload("main", 1, 1_000_000, 1, 1, 1, 2_000_000, 0, 0, 1, 3, 0, 0, 0)));
        publish(event(
                "r1",
                JournalSource.ORM,
                6_000_000,
                new OrmPayload("main", 2, 2_000_000, 1, 1, 2, 3_000_000, 1, 1_000_000, 2, 5, 0, 0, 0)));

        RouteStats route = aggregates.snapshot().routes().get(0);

        assertThat(route.authorization()).isEqualTo(new JournalAggregates.RouteAuthorization(0, 1, 0, 0, 0, 1));
        assertThat(route.orm().requests()).isEqualTo(1);
        assertThat(route.orm().flushes()).isEqualTo(3);
        assertThat(route.orm().autoFlushes()).isEqualTo(1);
        assertThat(route.orm().entityRequests()).isEqualTo(1);
        assertThat(route.orm().entities()).isEqualTo(5);
        assertThat(route.orm().time().count()).isEqualTo(1);
        assertThat(route.orm().time().totalMicros()).isEqualTo(9_000);
    }

    @Test
    void completedRequestAttributionExpiryIsBoundedAndReported() {
        for (int i = 0; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(http("r" + i, "/api/items", 200, 1_000));
        }
        publish(sql("r0", "select 1", 1_000, null, false));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.COMPLETED_REQUEST_ATTRIBUTIONS, 1L)
                .containsEntry(JournalAggregates.LATE_REQUEST_ATTRIBUTIONS, 1L);
        assertThat(snapshot.run().openRequests()).isZero();
        assertThat(snapshot.run().events()).containsEntry(JournalSource.SQL, 1L);
        assertThat(snapshot.routes().get(0).childCounts()).doesNotContainKey(JournalSource.SQL);
    }

    @Test
    void aTraceOnlyAiEdgeLeavingTheBoundedBufferSettlesUnderItsOwnerOrIsReportedUnowned() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", 1_050));
        for (int i = 0; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.edge().fromKey()).isEqualTo("GET /api/chat");
            assertThat(edge.edge().toKey()).isEqualTo("openai:gpt-4o");
            assertThat(edge.count()).isEqualTo(1);
        });
        assertThat(snapshot.routes()).singleElement().satisfies(route -> {
            assertThat(route.childCounts()).containsEntry(JournalSource.AI, 1L);
            assertThat(route.childNanos()).containsEntry(JournalSource.AI, 1_000_000L);
            assertThat(route.aiTokens()).isEqualTo(2);
        });
        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L)
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, (long) JournalAggregates.MAX_PENDING_REQUESTS + 1);
    }

    @Test
    void aTraceOnlyAiCallOutsideEveryRequestOfItsTraceIsUnownedRatherThanExpired() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", 9_000));
        publish(traceOnlyAi("no-request", 1_050));

        AggregatesSnapshot held = aggregates.snapshot();
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        AggregatesSnapshot settled = aggregates.snapshot();

        assertThat(held.edges()).isEmpty();
        assertThat(held.overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L)
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, 2L);
        assertThat(settled.edges()).isEmpty();
        assertThat(settled.overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L)
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, (long) JournalAggregates.MAX_PENDING_REQUESTS + 2);
    }

    @Test
    void anUnownedTraceOnlyAiEdgeIsReclaimedByItsRequestArrivingAfterItLeftTheBoundedBuffer() {
        publish(traceOnlyAi("trace-1", 1_050));
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        assertThat(aggregates.snapshot().overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, (long) JournalAggregates.MAX_PENDING_REQUESTS + 1);

        publish(tracedHttp("r1", "trace-1", 1_000));

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.edge().fromKey()).isEqualTo("GET /api/chat");
            assertThat(edge.edge().toKey()).isEqualTo("openai:gpt-4o");
            assertThat(edge.count()).isEqualTo(1);
        });
        assertThat(snapshot.routes())
                .singleElement()
                .satisfies(route -> assertThat(route.childCounts()).containsEntry(JournalSource.AI, 1L));
        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L)
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, (long) JournalAggregates.MAX_PENDING_REQUESTS);
    }

    @Test
    void aTraceOnlyAiCallThatSeveralRequestsOfItsTraceSpanIsUnattributableNotUnowned() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(tracedHttp("r2", "trace-1", 1_020));
        publish(traceOnlyAi("trace-1", 1_050));

        AggregatesSnapshot held = aggregates.snapshot();
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        AggregatesSnapshot settled = aggregates.snapshot();

        assertThat(held.edges()).isEmpty();
        assertThat(held.overflowed())
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 1L)
                .containsEntry(JournalAggregates.TRACE_AI_UNOWNED, 0L);
        assertThat(settled.edges()).isEmpty();
        assertThat(settled.overflowed()).containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 1L);
    }

    @Test
    void aSettledTraceOnlyAiEdgeIsRetractedWhenALaterRequestOfItsTraceAlsoSpansIt() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", 1_050));
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        assertThat(aggregates.snapshot().edges()).hasSize(1);

        publish(tracedHttp("r2", "trace-1", 1_020));

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.edges()).isEmpty();
        assertThat(snapshot.routes()).allSatisfy(route -> {
            assertThat(route.childCounts()).doesNotContainKey(JournalSource.AI);
            assertThat(route.aiTokens()).isZero();
        });
        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.EDGES, 0L)
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 1L);
    }

    @Test
    void retractingTheEarliestSettledObservationMovesTheEdgesFirstSeenToTheSurvivor() {
        assertSurvivorAfterRetraction(950, 1_010, 1_090);
    }

    @Test
    void retractingTheLatestSettledObservationMovesTheEdgesLastSeenToTheSurvivor() {
        assertSurvivorAfterRetraction(1_060, 1_090, 1_010);
    }

    /** Settles two AI calls under r1 (1000-1100 ms), then lets r2 starting at {@code laterStart} span one of them. */
    private void assertSurvivorAfterRetraction(long laterStart, long retracted, long survivor) {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", retracted));
        publish(traceOnlyAi("trace-1", survivor));
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        assertThat(aggregates.snapshot().edges())
                .singleElement()
                .satisfies(edge -> assertThat(edge.count()).isEqualTo(2));

        publish(tracedHttp("r2", "trace-1", laterStart));

        assertThat(aggregates.snapshot().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.count()).isEqualTo(1);
            assertThat(edge.firstSeenEpochMillis()).isEqualTo(survivor);
            assertThat(edge.lastSeenEpochMillis()).isEqualTo(survivor);
        });
    }

    @Test
    void aRequestThatMakesSettledClaimsAmbiguousRetractsThemBeforeAnEvictionCanFinalizeThem() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        for (int i = 0; i < 2 * JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("trace-1", 1_050));
        }
        for (int i = 1; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(http("other-" + i, "/api/items", 200, 1_000));
        }

        publish(tracedHttp("r2", "trace-1", 1_020));

        assertThat(aggregates.snapshot().edges()).isEmpty();
    }

    @Test
    void finalizingSettledClaimsAtTheEdgeCapLeavesTheReportedEdgesUnchanged() {
        int models = 11;
        int route = 0;
        for (int kept = 0; kept < JournalAggregates.MAX_EDGES - 1; route++) {
            for (int model = 0; model < models && kept < JournalAggregates.MAX_EDGES - 1; model++, kept++) {
                publish(event("filler-" + route, JournalSource.AI, 1_000, aiPayload("model-" + model)));
            }
            publish(http("filler-" + route, "/filler/" + route, 200, 1_000));
        }
        publish(tracedHttp("r1", "trace-1", 1_000));
        for (int i = 0; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("trace-1", 1_050));
        }
        publish(traceOnlyAi("trace-1", 1_060, "other-model"));
        for (int i = 1; i < JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(traceOnlyAi("unanchored-" + i, 2_000));
        }
        AggregatesSnapshot beforeFinalizing = aggregates.snapshot();

        publish(traceOnlyAi("unanchored-last", 2_000));

        AggregatesSnapshot afterFinalizing = aggregates.snapshot();
        assertThat(afterFinalizing.edges()).isEqualTo(beforeFinalizing.edges());
        assertThat(afterFinalizing.overflowed().get(JournalAggregates.EDGES))
                .isEqualTo(beforeFinalizing.overflowed().get(JournalAggregates.EDGES))
                .isEqualTo(1L);
        RouteStats beforeRoute = beforeFinalizing.routes().stream()
                .filter(routeStats -> routeStats.route().equals("GET /api/chat"))
                .findFirst()
                .orElseThrow();
        assertThat(afterFinalizing.routes())
                .filteredOn(routeStats -> routeStats.route().equals("GET /api/chat"))
                .singleElement()
                .satisfies(afterRoute -> {
                    assertThat(afterRoute.childCounts()).isEqualTo(beforeRoute.childCounts());
                    assertThat(afterRoute.childNanos()).isEqualTo(beforeRoute.childNanos());
                    assertThat(afterRoute.aiTokens()).isEqualTo(beforeRoute.aiTokens());
                });
    }

    @Test
    void aTraceOnlyAiCallWithoutAModelStillCountsForItsRoute() {
        publish(tracedHttp("r1", "trace-1", 1_000));
        publish(new RuntimeEvent(
                JournalSource.AI,
                1_050,
                3_000_000,
                null,
                null,
                "trace-1",
                null,
                null,
                false,
                new AiPayload(AiPayload.CHAT, "openai", null, 11L, 22L, "stop", true)));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.edges()).isEmpty();
        assertThat(snapshot.routes()).singleElement().satisfies(route -> {
            assertThat(route.childCounts()).containsEntry(JournalSource.AI, 1L);
            assertThat(route.childNanos()).containsEntry(JournalSource.AI, 3_000_000L);
            assertThat(route.aiTokens()).isEqualTo(33);
        });
    }

    @Test
    void anAttributedTraceOnlyAiEdgeOutlivesItsOwnersBoundedRecord() {
        publish(tracedHttp("r0", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", 1_050));
        assertThat(aggregates.snapshot().edges()).hasSize(1);

        for (int i = 1; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(http("r" + i, "/api/items", 200, 1_000));
        }

        AggregatesSnapshot snapshot = aggregates.snapshot();
        assertThat(snapshot.edges())
                .singleElement()
                .satisfies(edge -> assertThat(edge.count()).isEqualTo(1));
        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.COMPLETED_REQUEST_ATTRIBUTIONS, 1L)
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L);
    }

    @Test
    void aTraceOnlyAiEdgeWhoseRequestAttributionExpiredIsReportedRatherThanReassigned() {
        publish(tracedHttp("r0", "trace-1", 1_000));
        for (int i = 1; i <= JournalAggregates.MAX_PENDING_REQUESTS; i++) {
            publish(http("r" + i, "/api/items", 200, 1_000));
        }
        publish(tracedHttp("late", "trace-1", 1_000));
        publish(traceOnlyAi("trace-1", 1_050));

        AggregatesSnapshot snapshot = aggregates.snapshot();

        assertThat(snapshot.edges()).isEmpty();
        assertThat(snapshot.overflowed())
                .containsEntry(JournalAggregates.COMPLETED_REQUEST_ATTRIBUTIONS, 2L)
                .containsEntry(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 1L);
    }

    private static RuntimeEvent tracedHttp(String requestId, String traceId, long epochMillis) {
        return new RuntimeEvent(
                JournalSource.HTTP,
                epochMillis,
                100_000_000,
                requestId,
                null,
                traceId,
                "t",
                null,
                false,
                new HttpPayload("GET", "/api/chat", "/api/chat", null, 200, null));
    }

    private static RuntimeEvent traceOnlyAi(String traceId, long epochMillis) {
        return traceOnlyAi(traceId, epochMillis, "gpt-4o");
    }

    private static AiPayload aiPayload(String model) {
        return new AiPayload(AiPayload.CHAT, "openai", model, 1L, 1L, "stop", false);
    }

    private static RuntimeEvent traceOnlyAi(String traceId, long epochMillis, String model) {
        return new RuntimeEvent(
                JournalSource.AI, epochMillis, 1_000_000, null, null, traceId, null, null, false, aiPayload(model));
    }

    private static HttpPayload resourced(ResourceUsage usage) {
        return new HttpPayload("GET", "/api/orders", "/api/orders", null, 200, usage);
    }

    private static RuntimeEvent gc(String collector, long id, long millis) {
        return new RuntimeEvent(
                JournalSource.GC,
                1_000,
                millis * 1_000_000,
                null,
                null,
                null,
                null,
                null,
                false,
                new GcPayload(
                        collector,
                        id,
                        "end of minor GC",
                        "G1 Evacuation Pause",
                        !collector.contains("Cycles"),
                        2_000,
                        1_000));
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
