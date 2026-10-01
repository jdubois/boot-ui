package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Feed;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed.Filter;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JournalActivityFeedTests {

    private static final Function<JournalEntry, String> EVENT_ID = entry -> "run-" + entry.sequence();

    private final List<JournalEntry> entries = new ArrayList<>();
    private final JournalActivityFeed feed = new JournalActivityFeed(
            1_000,
            3,
            () -> RouteTemplateResolver.of(List.of(new MappingDto("GET", "/api/customers/{id}", "h", null, null))));

    @Test
    void childrenNestUnderTheirRequestByRequestIdWhateverThreadTheyRanOn() {
        add(sql("r1", "select * from orders where id = ?", 2, "worker-1", false), 1_000);
        add(event("r1", null, JournalSource.CACHE, 0, "other-thread", new CachePayload("orders", "MISS")), 1_001);
        add(event("r1", null, JournalSource.SECURITY, 0, null, new SecurityPayload("AUTHENTICATION_FAILURE")), 1_002);
        add(
                event(
                        "r1",
                        null,
                        JournalSource.EXCEPTION,
                        0,
                        null,
                        new ExceptionPayload("g1", "java.lang.IllegalState")),
                1_003);
        add(http("r1", "GET", "/api/orders/42", "/api/orders/{id}", 500, 30), 1_004);
        add(sql("r2", "select 1", 1, "worker-2", false), 1_005);

        Feed rendered = feed.render(entries, EVENT_ID, "run", Filter.NONE, 0);

        Map<String, ActivityEntryDto> byType = rendered.entries().stream()
                .filter(entry -> !entry.summary().equals("select 1"))
                .collect(Collectors.toMap(ActivityEntryDto::type, entry -> entry));
        ActivityEntryDto request = byType.get("REQUEST");
        assertThat(request.id()).isEqualTo("r1");
        assertThat(request.summary()).isEqualTo("GET /api/orders/42 → 500");
        assertThat(request.detail()).isEqualTo("/api/orders/{id}");
        assertThat(request.severity()).isEqualTo("ERROR");
        assertThat(request.profileable()).isTrue();
        assertThat(request.durationMs()).isEqualTo(30);
        assertThat(byType.get("SQL").parentId()).isEqualTo("r1");
        assertThat(byType.get("CACHE").parentId()).isEqualTo("r1");
        assertThat(byType.get("CACHE").severity()).isEqualTo("WARN");
        assertThat(byType.get("SECURITY").severity()).isEqualTo("WARN");
        assertThat(byType.get("EXCEPTION").summary()).isEqualTo("java.lang.IllegalState");
        assertThat(byType.get("EXCEPTION").method()).isEqualTo("GET");
        assertThat(byType.get("EXCEPTION").path()).isEqualTo("/api/orders/42");
        assertThat(rendered.entries())
                .filteredOn(entry -> entry.summary().equals("select 1"))
                .singleElement()
                .as("a request still in flight has no entry yet, so its child stays top-level")
                .satisfies(entry -> assertThat(entry.parentId()).isNull());
        assertThat(rendered.entries())
                .extracting(ActivityEntryDto::timestamp)
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(rendered.typeCounts()).containsEntry("SQL", 2).containsEntry("REQUEST", 1);
    }

    @Test
    void workOfAScheduledRunOrConsumedMessageNestsUnderItsExecution() {
        add(sql(null, "update stock set count = ?", 1, "scheduling-1", false, "e1"), 1_000);
        add(
                event(
                        null,
                        "e1",
                        JournalSource.SCHEDULED,
                        2_000_000_000L,
                        "scheduling-1",
                        new ScheduledPayload("StockJob.run", null)),
                1_001);
        add(
                event(
                        null,
                        "e2",
                        JournalSource.MESSAGING,
                        4,
                        "kafka-1",
                        new MessagingPayload("kafka", false, "orders", false, null)),
                1_002);
        add(
                event(
                        null,
                        "e2",
                        JournalSource.MESSAGING,
                        0,
                        "kafka-1",
                        new MessagingPayload("kafka", true, "invoices", false, null)),
                1_003);

        List<ActivityEntryDto> rendered =
                feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries();

        ActivityEntryDto scheduled = only(rendered, "SCHEDULED");
        assertThat(scheduled.id()).isEqualTo("e1");
        assertThat(scheduled.severity()).as("2 s against the 1 s threshold").isEqualTo("SLOW");
        assertThat(scheduled.parentId()).isNull();
        assertThat(only(rendered, "SQL").parentId()).isEqualTo(scheduled.id());
        ActivityEntryDto consumed = rendered.stream()
                .filter(entry -> entry.summary().equals("← orders"))
                .findFirst()
                .orElseThrow();
        assertThat(consumed.parentId()).isNull();
        assertThat(rendered)
                .filteredOn(entry -> entry.summary().equals("→ invoices"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.parentId()).isEqualTo(consumed.id()));
    }

    @Test
    void transactionsAndLogsAreRowsAndConnectionsAndCollectionsAreNot() {
        add(
                event("r1", null, JournalSource.TRANSACTION, 5, null, new TransactionPayload("OrderService.pay", true)),
                1_000);
        add(
                event(
                        "r1",
                        null,
                        JournalSource.LOG,
                        0,
                        null,
                        new LogPayload("com.acme.Orders", "ERROR", "Order {} failed", "java.io.IOException")),
                1_001);
        add(event("r1", null, JournalSource.CONNECTION, 5, null, new ConnectionPayload("db", 1, 2)), 1_002);
        add(
                new RuntimeEvent(
                        JournalSource.GC,
                        1_003,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        new GcPayload("G1 Young Generation", 1, "a", "c", true, 2, 1)),
                1_003);
        add(http("r1", "POST", "/api/orders", "/api/orders", 201, 10), 1_004);

        List<ActivityEntryDto> rendered =
                feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries();

        assertThat(rendered)
                .extracting(ActivityEntryDto::type)
                .containsExactlyInAnyOrder("REQUEST", "TRANSACTION", "LOG");
        ActivityEntryDto transaction = only(rendered, "TRANSACTION");
        assertThat(transaction.severity()).isEqualTo("WARN");
        assertThat(transaction.detail()).isEqualTo("rolled back");
        ActivityEntryDto log = only(rendered, "LOG");
        assertThat(log.summary()).isEqualTo("Order {} failed");
        assertThat(log.detail()).isEqualTo("com.acme.Orders · java.io.IOException");
        assertThat(log.severity()).isEqualTo("ERROR");
        assertThat(log.parentId()).isEqualTo("r1");
    }

    @Test
    void emailsAndFaultToleranceOutcomesAreRowsCarryingOnlyMetadata() {
        add(event("r1", null, JournalSource.MAIL, -1, null, new MailPayload(2, 0, false)), 1_000);
        add(
                event(
                        "r1",
                        null,
                        JournalSource.FAULT_TOLERANCE,
                        40_000_000,
                        null,
                        new FaultTolerancePayload(
                                "payments",
                                "CIRCUIT_BREAKER",
                                "Gateway#pay",
                                "STATE_TRANSITION",
                                null,
                                "OPEN",
                                null,
                                false,
                                true)),
                1_001);
        add(http("r1", "POST", "/api/pay", "/api/pay", 503, 50), 1_002);

        List<ActivityEntryDto> rendered =
                feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries();

        ActivityEntryDto mail = only(rendered, "MAIL");
        assertThat(mail.summary()).isEqualTo("Email to 2 recipients");
        assertThat(mail.detail()).isEqualTo("dev-trap: not sent");
        assertThat(mail.severity()).isEqualTo("WARN");
        assertThat(mail.parentId()).isEqualTo("r1");
        ActivityEntryDto ft = only(rendered, "FAULT_TOLERANCE");
        assertThat(ft.summary()).isEqualTo("STATE_TRANSITION payments (circuit breaker)");
        assertThat(ft.detail()).isEqualTo("Gateway#pay · state OPEN");
        assertThat(ft.severity()).isEqualTo("WARN");
        assertThat(ft.durationMs()).isEqualTo(40);
        assertThat(ft.parentId()).isEqualTo("r1");
    }

    @Test
    void liveRowsAreCompletedWithTheMaskedDetailTheBuffersStillHoldJoinedByIdentity() {
        add(
                event(
                        "r1",
                        null,
                        JournalSource.EXCEPTION,
                        -1,
                        null,
                        new ExceptionPayload("g1", "java.lang.IllegalStateException")),
                1_000);
        add(event("r1", null, JournalSource.MAIL, -1, null, new MailPayload(1, 0, true)), 1_001);
        add(
                event(
                        "r1",
                        null,
                        JournalSource.EXCEPTION,
                        -1,
                        null,
                        new ExceptionPayload("evicted", "java.io.IOException")),
                1_002);
        add(http("r1", "GET", "/api/secure", "/api/secure", 500, 5), 1_003);
        JournalRowDetails details = JournalRowDetails.of(
                List.of(new HttpExchangeDto(
                        "x1",
                        Instant.EPOCH,
                        "GET",
                        "/api/secure",
                        null,
                        null,
                        500,
                        null,
                        5L,
                        null,
                        null,
                        "admin",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        "r1")),
                List.of(new ExceptionGroupDto(
                        "g1",
                        "java.lang.IllegalStateException",
                        "boom ****",
                        2,
                        0,
                        0,
                        "OrderService.java:42",
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        "r1",
                        null)),
                List.of(new EmailMessageDto(
                        "email-7",
                        1_001,
                        "noreply@example.com",
                        List.of("a@example.com"),
                        List.of(),
                        List.of(),
                        "Welcome",
                        null,
                        null,
                        List.of(),
                        true,
                        null,
                        null,
                        "r1")));

        List<ActivityEntryDto> rendered =
                feed.render(entries, EVENT_ID, "run", Filter.NONE, 0, details).entries();
        List<ActivityEntryDto> plain =
                feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries();

        assertThat(only(rendered, "REQUEST").securedPrincipal()).isEqualTo("admin");
        assertThat(rendered)
                .filteredOn(entry -> entry.type().equals("EXCEPTION"))
                .extracting(ActivityEntryDto::summary, ActivityEntryDto::detail)
                .containsExactlyInAnyOrder(
                        tuple("java.lang.IllegalStateException: boom ****", "OrderService.java:42"),
                        tuple("java.io.IOException", null));
        ActivityEntryDto mail = only(rendered, "MAIL");
        assertThat(mail.id())
                .as("the Email panel's id, so the row opens that message")
                .isEqualTo("email-7");
        assertThat(mail.summary()).isEqualTo("Welcome");
        assertThat(mail.detail()).isEqualTo("to a@example.com");
        assertThat(only(plain, "MAIL").summary())
                .as("without details, metadata only")
                .isEqualTo("Email to 1 recipient");
        assertThat(only(plain, "REQUEST").securedPrincipal()).isNull();
    }

    @Test
    void aRequestRepeatingOneSelectAtTheThresholdIsFlaggedAsAnNPlusOne() {
        for (int i = 0; i < 3; i++) {
            add(sql("r1", "select * from lines   where order_id = ?", 1, "t", false), 1_000 + i);
            add(sql("r2", "select * from lines where order_id = ?", 1, "t", false), 1_000 + i);
        }
        add(sql("r2", "update lines set qty = ?", 1, "t", false), 1_010);
        add(http("r1", "GET", "/api/a", "/api/a", 200, 1), 1_020);
        add(http("r2", "GET", "/api/b", "/api/b", 200, 1), 1_021);
        add(http("r3", "GET", "/api/c", "/api/c", 200, 1), 1_022);

        Map<String, Boolean> flagged = feed.render(entries, EVENT_ID, "run", Filter.NONE, 0).entries().stream()
                .filter(entry -> entry.type().equals("REQUEST"))
                .collect(Collectors.toMap(ActivityEntryDto::id, ActivityEntryDto::sqlNPlusOneSuspected));

        assertThat(flagged).containsEntry("r1", true).containsEntry("r2", true).containsEntry("r3", false);
    }

    @Test
    void filtersSelectARouteARequestARunWorkOutsideRequestsTypeSeveritySinceAndLimit() {
        add(sql("r1", "select 1", 1, "t", false), 1_000);
        add(http("r1", "GET", "/api/customers/7", null, 200, 1), 1_001);
        add(sql("r2", "select 2", 1, "t", true), 1_002);
        add(http("r2", "GET", "/api/orders/9", "/api/orders/{id}", 200, 1), 1_003);
        add(
                event(null, "e1", JournalSource.SCHEDULED, 1, "scheduling-1", new ScheduledPayload("Job.run", null)),
                1_004);
        add(sql(null, "select 3", 1, "pool-1-thread-1", false), 1_005);

        assertThat(ids(new Filter(null, null, 0, "GET /api/customers/{id}", null, null, false)))
                .as("by declared route, with the request's children")
                .containsExactly("r1", "run-1");
        assertThat(ids(new Filter(null, null, 0, "/api/orders/{id}", null, null, false)))
                .containsExactly("r2", "run-3");
        assertThat(ids(new Filter(null, null, 0, null, null, "r2", false))).containsExactly("r2", "run-3");
        assertThat(ids(new Filter(null, null, 0, null, null, null, true)))
                .as("a scheduled run is identified by its execution id")
                .containsExactly("run-6", "e1");
        assertThat(ids(new Filter(null, null, 0, null, "another-run", null, false)))
                .isEmpty();
        assertThat(ids(new Filter(null, null, 0, null, "run", null, false))).hasSize(6);
        assertThat(ids(new Filter("sql", "error", 0, null, null, null, false))).containsExactly("run-3");
        assertThat(ids(new Filter(null, null, 1_003, null, null, null, false))).containsExactly("run-6", "e1");
        assertThat(feed.render(entries, EVENT_ID, "run", Filter.NONE, 2).entries())
                .hasSize(2);
    }

    @Test
    void kpisAreComputedFromTheRetainedEvents() {
        add(http("r1", "GET", "/api/a", "/api/a", 200, 10), 0);
        add(http("r2", "GET", "/api/b/1", "/api/b/{id}", 500, 90), 60_000);
        add(sql("r1", "select 1", 7, "t", false), 1);
        add(
                event(
                        "r1",
                        null,
                        JournalSource.REST_CLIENT,
                        20_000_000,
                        null,
                        new RestClientPayload("GET", "x:443", "/y", 503, "RestClient", false)),
                2);
        add(event("r1", null, JournalSource.CACHE, 0, null, new CachePayload("c", "HIT")), 3);
        add(event("r1", null, JournalSource.CACHE, 0, null, new CachePayload("c", "MISS")), 4);
        add(event("r1", null, JournalSource.CACHE, 0, null, new CachePayload("c", "PUT")), 5);
        add(event("r1", null, JournalSource.EXCEPTION, 0, null, new ExceptionPayload("g1", "E")), 6);
        add(event("r2", null, JournalSource.EXCEPTION, 0, null, new ExceptionPayload("g1", "E")), 7);
        add(event(null, "e1", JournalSource.SCHEDULED, 1, null, new ScheduledPayload("Job", "java.lang.Error")), 8);

        ActivityKpiDto kpis = feed.kpis(entries, "UP");

        assertThat(kpis.requestsPerMinute()).isEqualTo(2);
        assertThat(kpis.errorRatePercent()).isEqualTo(50);
        assertThat(kpis.latencySampleCount()).isEqualTo(2);
        assertThat(kpis.slowestEndpoint()).isEqualTo("/api/b/1");
        assertThat(kpis.slowestEndpointRoute()).isEqualTo("/api/b/{id}");
        assertThat(kpis.slowestEndpointRouteId()).isEqualTo("GET /api/b/{id}");
        assertThat(kpis.slowestQueryMs()).isEqualTo(7);
        assertThat(kpis.restCallErrorRatePercent()).isEqualTo(100);
        assertThat(kpis.restCallP95LatencyMs()).isEqualTo(20);
        assertThat(kpis.cacheHitRatioPercent()).isEqualTo(50);
        assertThat(kpis.activeExceptionCount()).isEqualTo(1);
        assertThat(kpis.scheduledTaskFailureCount()).isEqualTo(1);
        assertThat(kpis.healthStatus()).isEqualTo("UP");
        assertThat(feed.kpis(List.of(), null).p50LatencyMs()).isNull();
    }

    private List<String> ids(Filter filter) {
        return feed.render(entries, EVENT_ID, "run", filter, 0).entries().stream()
                .map(ActivityEntryDto::id)
                .toList();
    }

    private static ActivityEntryDto only(List<ActivityEntryDto> rendered, String type) {
        return rendered.stream()
                .filter(entry -> entry.type().equals(type))
                .reduce((a, b) -> {
                    throw new AssertionError("more than one " + type);
                })
                .orElseThrow();
    }

    private void add(RuntimeEvent event, long epochMillis) {
        RuntimeEvent timed = new RuntimeEvent(
                event.source(),
                epochMillis,
                event.durationNanos(),
                event.requestId(),
                event.executionId(),
                event.traceId(),
                event.spanId(),
                event.thread(),
                event.threadKind(),
                event.failedOrSlow(),
                event.payload());
        entries.add(new JournalEntry(entries.size() + 1, timed, timed.estimatedBytes()));
    }

    private static RuntimeEvent http(
            String requestId, String method, String path, String template, int status, long millis) {
        return event(
                requestId,
                null,
                JournalSource.HTTP,
                millis * 1_000_000,
                "http-1",
                new HttpPayload(method, path, template, null, status));
    }

    private static RuntimeEvent sql(String requestId, String sql, long millis, String thread, boolean failed) {
        return sql(requestId, sql, millis, thread, failed, null);
    }

    private static RuntimeEvent sql(
            String requestId, String sql, long millis, String thread, boolean failed, String executionId) {
        return event(
                requestId,
                executionId,
                JournalSource.SQL,
                millis * 1_000_000,
                thread,
                new SqlPayload(sql, null, "dataSource", failed));
    }

    private static RuntimeEvent event(
            String requestId,
            String executionId,
            JournalSource source,
            long nanos,
            String thread,
            RuntimeEventPayload payload) {
        CorrelationContext context = requestId != null
                ? CorrelationContext.forRequest(requestId)
                : executionId != null ? CorrelationContext.forExecution(executionId) : CorrelationContext.NONE;
        boolean failed = payload instanceof SqlPayload sql && sql.failed();
        return RuntimeEvent.of(source, 0, nanos, context, thread, null, failed, payload);
    }
}
