package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.inventory.CodeChanges;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedCode;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.FaultTolerancePayload;
import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The seeded cases and counterexamples of every Runtime Insights observation kind ({@code docs/PLAN-v2.md} §2.2's
 * honesty measure, §5.5, M4-18e), in one table that {@link ObservationHonestyHarnessTests} replays.
 *
 * <p>Each fixture records its events in the order the recorders publish them: a request's children as each one
 * completes, its HTTP event when the response is written, and work that outlives the response after it. The seeds use
 * the payload shapes of each kind's own tests and the routes the sample applications seed ({@code InsightSeedController}
 * and its counterexamples beside it), so a kind's justified case and the shapes that must not produce it are listed
 * side by side.</p>
 */
final class ObservationFixtures {

    static final long MS = 1_000_000L;

    /** How long an event takes before its recorder marks it slow, in these fixtures. */
    static final long SLOW = 1_000 * MS;

    /** Whether a fixture is a kind's seeded case or one of its counterexamples. */
    enum Role {
        POSITIVE,
        COUNTEREXAMPLE
    }

    /**
     * One seeded case or counterexample.
     *
     * @param kind the observation kind it is a fixture of
     * @param role whether the kind must report it or must not
     * @param name what it seeds
     * @param stack the stack it runs on
     * @param alsoFires other kinds this seed legitimately produces, which the harness does not forbid
     * @param tolerated rows of its own kind a counterexample may still show, such as a retry listed apart as recovered
     * @param setup what the service needs besides the journal, such as the proxy boundaries or the agent
     * @param events its events, in publication order
     */
    record Fixture(
            String kind,
            Role role,
            String name,
            InsightsStack stack,
            Set<String> alsoFires,
            Predicate<RuntimeObservationDto> tolerated,
            Consumer<RuntimeInsightsService> setup,
            List<RuntimeEvent> events) {

        @Override
        public String toString() {
            return kind + " " + (role == Role.POSITIVE ? "seeded case" : "counterexample") + ": " + name;
        }
    }

    private ObservationFixtures() {}

    /** Every fixture, kind by kind in report order. */
    static List<Fixture> all() {
        List<Fixture> fixtures = new ArrayList<>();
        routeTimeBreakdown(fixtures);
        exceptionHotspots(fixtures);
        errorsBehind2xx(fixtures);
        repeatedSelects(fixtures);
        connectionsPerRequest(fixtures);
        safeMethodDml(fixtures);
        proxyBypass(fixtures);
        anonymousAccess(fixtures);
        splitTransactionWrites(fixtures);
        transactionalListenerSkipped(fixtures);
        afterCommitWrites(fixtures);
        ormObservations(fixtures);
        transactionAcrossRemoteCall(fixtures);
        lazySqlAfterHandler(fixtures);
        eventLoopBlocking(fixtures);
        gcObservations(fixtures);
        aiUsageByRoute(fixtures);
        frameworkWarningsByRoute(fixtures);
        workAfterResponse(fixtures);
        changedCodeNotExecuted(fixtures);
        return List.copyOf(fixtures);
    }

    // ---- route-time-breakdown -----------------------------------------------------------------------------------

    private static void routeTimeBreakdown(List<Fixture> fixtures) {
        fixtures.add(positive(RouteTimeBreakdown.KIND, "a cold request then five warm ones with phases and SQL")
                .seed(recording -> {
                    recording.get("/api/orders/{id}").duration(400 * MS).timing(new RequestTiming(0, -1, -1, -1));
                    for (int i = 0; i < 5; i++) {
                        warmOrder(recording);
                    }
                }));
        fixtures.add(counterexample(RouteTimeBreakdown.KIND, "four warm requests are not enough to break a route down")
                .toleratingInsufficient()
                .seed(recording -> {
                    recording.get("/api/orders/{id}").duration(400 * MS).timing(new RequestTiming(0, -1, -1, -1));
                    for (int i = 0; i < 4; i++) {
                        warmOrder(recording);
                    }
                }));
    }

    private static void warmOrder(Recording recording) {
        long start = recording.clock();
        recording
                .get("/api/orders/{id}")
                .duration(40 * MS)
                .timing(new RequestTiming(start, 2 * MS, 5 * MS, 35 * MS))
                .children(
                        sql(new SqlPayload("select 1", null, "db", false, null, null, start + 20 * MS))
                                .took(10 * MS),
                        sql(new SqlPayload("select 2", null, "db", false, null, null, start + 25 * MS))
                                .took(10 * MS),
                        new Ev(
                                JournalSource.REST_CLIENT,
                                4 * MS,
                                new RestClientPayload(
                                        "GET",
                                        "stock:8080",
                                        "/items",
                                        200,
                                        "RestClient",
                                        false,
                                        null,
                                        start + 30 * MS)),
                        new Ev(
                                JournalSource.CONNECTION,
                                30 * MS,
                                new ConnectionPayload("db", 2 * MS, 2, start + 10 * MS)));
    }

    // ---- exception-hotspots -------------------------------------------------------------------------------------

    private static void exceptionHotspots(List<Fixture> fixtures) {
        fixtures.add(positive(ExceptionHotspots.KIND, "a request that failed with a lazy-loading exception")
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .status(500)
                        .children(exception("lazy", "org.hibernate.LazyInitializationException"))));
        fixtures.add(counterexample(ExceptionHotspots.KIND, "an exception no request or execution owned")
                .seed(recording -> {
                    recording.unowned(exception("startup", "java.lang.IllegalStateException"));
                    recording.get("/api/orders/{id}");
                }));
    }

    // ---- errors-behind-2xx --------------------------------------------------------------------------------------

    private static void errorsBehind2xx(List<Fixture> fixtures) {
        fixtures.add(positive(ErrorsBehind2xx.KIND, "a rolled-back import answered 200, as the sample's import seed")
                .alsoFires(ExceptionHotspots.KIND)
                .seed(recording -> {
                    for (int i = 0; i < 2; i++) {
                        recording
                                .post("/api/insights/orders/{id}/import")
                                .children(
                                        exception("e" + i, "java.lang.IllegalStateException"),
                                        transaction(new TransactionPayload(
                                                "OrderService.importOrder", true, false, false, 1)));
                    }
                }));
        fixtures.add(counterexample(
                        ErrorsBehind2xx.KIND, "a 404 the client library reported and a savepoint rolled back alone")
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .children(
                                new Ev(
                                        JournalSource.REST_CLIENT,
                                        MS,
                                        new RestClientPayload(
                                                "GET", "stock:8080", "/items/1", 404, "RestClient", true)),
                                transaction(new TransactionPayload("StockService.reserve", true, true, true, 2)))));
        fixtures.add(counterexample(ErrorsBehind2xx.KIND, "an intentional retry that recovered is listed apart")
                .alsoFires(ExceptionHotspots.KIND)
                .tolerating(row -> row.sentence().endsWith("a retry or fallback recovered."))
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .children(
                                exception("pricing", "java.lang.IllegalArgumentException"),
                                policy("RETRY", "IllegalArgumentException"),
                                policy("SUCCESS", null))));
        fixtures.add(counterexample(ErrorsBehind2xx.KIND, "a failure answered 500 is not hidden behind a success")
                .alsoFires(ExceptionHotspots.KIND)
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .status(500)
                        .children(exception("e", "java.lang.IllegalStateException"))));
    }

    private static Ev policy(String outcome, String category) {
        return new Ev(
                JournalSource.FAULT_TOLERANCE,
                MS,
                new FaultTolerancePayload(
                        "pricing",
                        "RETRY",
                        "Pricing.lookup",
                        outcome,
                        1,
                        null,
                        category,
                        "RETRY".equals(outcome) || "RETRY_EXHAUSTED".equals(outcome),
                        true));
    }

    // ---- repeated-selects ---------------------------------------------------------------------------------------

    private static void repeatedSelects(List<Fixture> fixtures) {
        fixtures.add(positive(RepeatedSelects.KIND, "the sample's line-by-line loop after its parent select")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording.get("/api/insights/orders").children(lineByLine(6));
                    }
                }));
        fixtures.add(positive(RepeatedSelects.KIND, "a scheduled job repeating a select after its parent")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording.scheduled("OrderJob.run", lineByLine(5));
                    }
                }));
        fixtures.add(positive(RepeatedSelects.KIND, "a Kafka listener repeating a select after its parent")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        Ev[] loop = lineByLine(5);
                        loop[0] = loop[0].at(0);
                        recording.consumed("orders", loop);
                    }
                }));
        fixtures.add(counterexample(RepeatedSelects.KIND, "the sample's joined read, one statement per request")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/insights/orders/joined")
                                .children(sql("select o.id, l.sku from insight_orders o join insight_order_lines l"
                                        + " on l.order_id = o.id"));
                    }
                }));
        fixtures.add(counterexample(RepeatedSelects.KIND, "a batch loop with no parent statement before it")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        Ev[] items = new Ev[6];
                        Arrays.fill(items, sql("select * from items where id = ?"));
                        recording.get("/api/batch").children(items);
                    }
                }));
        fixtures.add(counterexample(RepeatedSelects.KIND, "a Kafka listener reading its order in one join")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording.consumed(
                                "orders",
                                sql("select * from orders o join lines l on l.order_id = o.id")
                                        .at(0),
                                sql("update orders set seen = true where id = ?"));
                    }
                }));
        fixtures.add(counterexample(RepeatedSelects.KIND, "a scheduled job reading everything in one join")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording.scheduled(
                                "OrderJob.run", sql("select * from orders o join lines l on l.order_id = o.id"));
                    }
                }));
    }

    private static Ev[] lineByLine(int repeats) {
        Ev[] events = new Ev[1 + repeats];
        events[0] = sql("select id, customer from insight_orders order by id");
        for (int i = 0; i < repeats; i++) {
            events[i + 1] = sql("select sku, quantity from insight_order_lines where order_id = ?");
        }
        return events;
    }

    // ---- connections-per-request --------------------------------------------------------------------------------

    private static void connectionsPerRequest(List<Fixture> fixtures) {
        fixtures.add(positive(ConnectionsPerRequest.KIND, "a REQUIRES_NEW connection inside the outer one")
                .setup(service -> service.setPoolSizes(name -> name.equals("db") ? 10 : null))
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/confirm")
                        .children(connection("db", 10 * MS, 5 * MS), connection("db", 1_000, 50 * MS))));
        fixtures.add(counterexample(ConnectionsPerRequest.KIND, "two connections checked out one after the other")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/ship")
                        .children(connection("db", 0, 10 * MS), connection("db", 10 * MS, 10 * MS))));
        fixtures.add(counterexample(ConnectionsPerRequest.KIND, "two data sources each holding one connection")
                .seed(recording -> recording
                        .post("/api/report")
                        .children(connection("audit", 1_000, 5 * MS), connection("db", 0, 10 * MS))));
    }

    private static Ev connection(String dataSource, long checkoutNanos, long heldNanos) {
        return new Ev(JournalSource.CONNECTION, heldNanos, new ConnectionPayload(dataSource, 0, 1, checkoutNanos));
    }

    // ---- safe-method-dml ----------------------------------------------------------------------------------------

    private static void safeMethodDml(List<Fixture> fixtures) {
        fixtures.add(positive(SafeMethodDml.KIND, "the sample's audit write on GET")
                .seed(recording -> recording
                        .get("/api/insights/orders/{id}")
                        .children(
                                sql("select * from insight_orders where id = ?"),
                                sql("insert into insight_audit (order_id) values (?)"))));
        fixtures.add(counterexample(SafeMethodDml.KIND, "the sample's public catalog read")
                .seed(recording -> recording.get("/api/sample/products").children(sql("select * from products"))));
        fixtures.add(counterexample(SafeMethodDml.KIND, "a write on POST")
                .seed(recording -> recording.post("/api/orders").children(sql("insert into orders (id) values (5)"))));
        fixtures.add(counterexample(SafeMethodDml.KIND, "a GET whose write failed")
                .seed(recording -> recording
                        .get("/api/cart")
                        .children(sql(new SqlPayload("delete from carts where id = 9", null, "db", true)))));
    }

    // ---- proxy-bypass -------------------------------------------------------------------------------------------

    private static final String REPOSITORY = "com.example.OrderRepository.save(OrderRepository.java:10)";
    private static final String PLACE = "com.example.OrderService.place(OrderService.java:20)";
    private static final String CHECKOUT = "com.example.OrderService.checkout(OrderService.java:12)";
    private static final String CONTROLLER = "com.example.OrderController.create(OrderController.java:9)";
    private static final String PRICE = "com.example.PriceService.price(PriceService.java:30)";
    private static final String NOTIFY = "com.example.Notifier.notifyShipped(Notifier.java:40)";

    /** The sample's proxied methods: a {@code @Transactional}, a {@code @Cacheable("prices")}, and an {@code @Async}. */
    static final ProxyBoundaries BOUNDARIES = frame -> Map.of(
                    PLACE, new ProxyBoundaries.Boundary(true, Set.of(), false),
                    PRICE, new ProxyBoundaries.Boundary(false, Set.of("prices"), false),
                    NOTIFY, new ProxyBoundaries.Boundary(false, Set.of(), true))
            .getOrDefault(frame, ProxyBoundaries.Boundary.NONE);

    private static void proxyBypass(List<Fixture> fixtures) {
        Consumer<RuntimeInsightsService> boundaries = service -> service.setProxyBoundaries(BOUNDARIES);
        fixtures.add(positive(ProxyBypass.KIND, "the sample's self-invoked @Transactional recalculation")
                .setup(boundaries)
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/recalculate")
                        .children(framedStatement(REPOSITORY, PLACE, CHECKOUT))));
        fixtures.add(positive(ProxyBypass.KIND, "a @Cacheable method that never reached its cache")
                .setup(boundaries)
                .seed(recording -> recording.get("/api/prices").children(framedStatement(PRICE))));
        fixtures.add(positive(ProxyBypass.KIND, "an @Async method that ran on the request thread")
                .setup(boundaries)
                .seed(recording -> recording.post("/api/ship").children(framedStatement(NOTIFY))));
        fixtures.add(counterexample(ProxyBypass.KIND, "the sample's recalculation through the bean, in a transaction")
                .setup(boundaries)
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/recalculate-through-bean")
                        .children(
                                framedStatement(REPOSITORY, PLACE, CONTROLLER),
                                transaction(new TransactionPayload("OrderService.place", false, false, false, 500))
                                        .took(2_000))));
        fixtures.add(counterexample(ProxyBypass.KIND, "a @Cacheable method that read its cache first")
                .setup(boundaries)
                .seed(recording -> recording
                        .get("/api/prices")
                        .children(
                                new Ev(JournalSource.CACHE, 0, new CachePayload("prices", "MISS")),
                                framedStatement(PRICE))));
        fixtures.add(counterexample(ProxyBypass.KIND, "two concurrent @Cacheable reads, each reading its cache first")
                .setup(boundaries)
                .seed(recording -> recording.concurrently(() -> {
                    for (String thread : List.of("http-1", "http-2")) {
                        recording
                                .get("/api/prices")
                                .thread(thread)
                                .children(
                                        new Ev(JournalSource.CACHE, 0, new CachePayload("prices", "MISS")),
                                        framedStatement(PRICE));
                    }
                })));
        fixtures.add(counterexample(ProxyBypass.KIND, "an @Async method that ran on its executor's thread")
                .setup(boundaries)
                .seed(recording -> recording
                        .post("/api/ship")
                        .children(framedStatement(NOTIFY).onThread("task-1"))));
    }

    /** A statement issued through {@code frames}, innermost first: a write for an order, a read otherwise. */
    private static Ev framedStatement(String... frames) {
        return new Ev(
                JournalSource.SQL,
                100,
                new SqlPayload(
                        frames[0].equals(REPOSITORY)
                                ? "insert into orders values (?)"
                                : "select * from prices where sku = ?",
                        frames[0],
                        "db",
                        false,
                        ApplicationFrames.of(List.of(frames)),
                        null,
                        1_000));
    }

    // ---- anonymous-data-reach and anonymous-success-on-restricted-route -----------------------------------------

    private static void anonymousAccess(List<Fixture> fixtures) {
        fixtures.add(positive(AnonymousDataReach.KIND, "the sample's anonymous reset of the debug totals")
                .seed(recording -> {
                    for (int i = 0; i < 2; i++) {
                        recording
                                .post("/api/insights/debug/reset-totals")
                                .children(
                                        decision("ANONYMOUS", true, null), sql("update insight_totals set amount = 0"));
                    }
                }));
        fixtures.add(counterexample(AnonymousDataReach.KIND, "the sample's public catalog read")
                .seed(recording -> recording
                        .get("/api/sample/products")
                        .children(decision("ANONYMOUS", true, null), sql("select * from products"))));
        fixtures.add(counterexample(AnonymousDataReach.KIND, "an authenticated write")
                .seed(recording -> recording
                        .post("/api/reviews")
                        .status(201)
                        .children(decision("AUTHENTICATED", true, null), sql("insert into reviews values (?, ?)"))));
        fixtures.add(counterexample(AnonymousDataReach.KIND, "a write whose authentication was never decided")
                .seed(recording -> recording
                        .post("/api/contact")
                        .status(201)
                        .children(sql("insert into contact_messages values (?)"))));
        fixtures.add(counterexample(AnonymousDataReach.KIND, "an anonymous write the rules denied")
                .seed(recording ->
                        recording.post("/api/reviews").status(403).children(decision("ANONYMOUS", false, null))));

        fixtures.add(positive(
                        AnonymousSuccessOnRestrictedRoute.KIND,
                        "the sample's case-sensitive matcher: payroll denied, PAYROLL served")
                .seed(recording -> {
                    recording
                            .get("/api/insights/reports/{name}", "/api/insights/reports/payroll")
                            .status(403)
                            .children(decision("ANONYMOUS", false, null));
                    recording
                            .get("/api/insights/reports/{name}", "/api/insights/reports/PAYROLL")
                            .children(decision("ANONYMOUS", true, null));
                }));
        fixtures.add(counterexample(AnonymousSuccessOnRestrictedRoute.KIND, "the sample's public catalog read")
                .seed(recording -> {
                    for (int i = 0; i < 2; i++) {
                        recording.get("/api/sample/products").children(decision("ANONYMOUS", true, null));
                    }
                }));
        fixtures.add(counterexample(
                        AnonymousSuccessOnRestrictedRoute.KIND, "a restricted route only authenticated requests passed")
                .seed(recording -> {
                    recording
                            .get("/api/admin")
                            .children(decision("AUTHENTICATED", true, "hasAnyAuthority(ROLE_ADMIN)"));
                    recording.get("/api/admin").status(401).children(decision("ANONYMOUS", false, null));
                }));
    }

    private static Ev decision(String authentication, boolean granted, String rule) {
        return new Ev(
                JournalSource.AUTHORIZATION,
                1_000,
                new AuthorizationPayload("REQUEST", null, rule, authentication, granted, 0));
    }

    // ---- split-transaction-writes -------------------------------------------------------------------------------

    private static void splitTransactionWrites(List<Fixture> fixtures) {
        fixtures.add(positive(SplitTransactionWrites.KIND, "a transaction, then an autocommit write after it")
                .seed(recording -> recording
                        .post("/api/orders")
                        .children(
                                sqlAt("insert into orders values (1)", RequestPhase.HANDLER, 150),
                                transactionAt("OrderService.place", false, false, false, 100, 200),
                                sqlAt("insert into audit values (1)", RequestPhase.HANDLER, 250))));
        fixtures.add(positive(SplitTransactionWrites.KIND, "the sample's REQUIRES_NEW write inside the outer one")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/confirm")
                        .children(
                                sqlAt("insert into orders values (2)", RequestPhase.HANDLER, 120),
                                sqlAt("insert into audit values (2)", RequestPhase.HANDLER, 135),
                                transactionAt("AuditService.log", true, false, false, 130, 140),
                                sqlAt("insert into lines values (2)", RequestPhase.HANDLER, 150),
                                transactionAt("OrderService.place", false, false, false, 100, 200))));
        fixtures.add(counterexample(SplitTransactionWrites.KIND, "the sample's writes in one transaction")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/ship")
                        .children(
                                sqlAt("update insight_orders set status = 'SHIPPED'", RequestPhase.HANDLER, 120),
                                sqlAt("insert into insight_audit values (3)", RequestPhase.HANDLER, 150),
                                transactionAt("OrderService.ship", false, false, false, 100, 200))));
        fixtures.add(counterexample(
                        SplitTransactionWrites.KIND, "two concurrent requests, each writing in one transaction")
                .seed(recording -> recording.concurrently(() -> {
                    for (String thread : List.of("http-1", "http-2")) {
                        recording
                                .post("/api/insights/orders/{id}/ship")
                                .thread(thread)
                                .children(
                                        sqlAt(
                                                "update insight_orders set status = 'SHIPPED'",
                                                RequestPhase.HANDLER,
                                                120),
                                        sqlAt("insert into insight_audit values (3)", RequestPhase.HANDLER, 150),
                                        transactionAt("OrderService.ship", false, false, false, 100, 200));
                    }
                })));
        fixtures.add(counterexample(SplitTransactionWrites.KIND, "slow writes in one transaction, kept longer than it")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/ship")
                        .duration(3 * SLOW)
                        .children(
                                sqlAt("update insight_orders set status = 'SHIPPED'", RequestPhase.HANDLER, 120)
                                        .took(SLOW),
                                sqlAt("insert into insight_audit values (3)", RequestPhase.HANDLER, 150)
                                        .took(SLOW),
                                transactionAt("OrderService.ship", false, false, false, 100, 200))));
        fixtures.add(counterexample(
                        SplitTransactionWrites.KIND, "a NESTED savepoint commits with the outer transaction")
                .seed(recording -> recording
                        .post("/api/orders")
                        .children(
                                sqlAt("update stock set n = 1", RequestPhase.HANDLER, 135),
                                transactionAt("StockService.reserve", true, true, false, 130, 140),
                                sqlAt("insert into orders values (3)", RequestPhase.HANDLER, 150),
                                transactionAt("OrderService.place", false, false, false, 100, 200))));
        fixtures.add(counterexample(SplitTransactionWrites.KIND, "a rolled-back transaction leaves one committed write")
                .alsoFires(ErrorsBehind2xx.KIND)
                .seed(recording -> recording
                        .post("/api/orders")
                        .children(
                                sqlAt("insert into orders values (4)", RequestPhase.HANDLER, 150),
                                transactionAt("OrderService.place", false, false, true, 100, 200),
                                sqlAt("insert into audit values (4)", RequestPhase.HANDLER, 250))));
    }

    // ---- transactional-listener-skipped -------------------------------------------------------------------------

    private static final String PLACED = "io.github.jdubois.bootui.sample.insights.InsightOrderEvents$OrderPlaced";

    private static void transactionalListenerSkipped(List<Fixture> fixtures) {
        fixtures.add(positive(TransactionalListenerSkipped.KIND, "the sample's notify, published in no transaction")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/notify")
                        .children(
                                appEvent(AppEventPayload.published(PLACED, 1)),
                                appEvent(AppEventPayload.listener(
                                        PLACED,
                                        "OrderListener#onPlaced",
                                        "AFTER_COMMIT",
                                        AppEventPayload.SKIPPED_NO_TRANSACTION,
                                        null,
                                        -1)))));
        fixtures.add(counterexample(
                        TransactionalListenerSkipped.KIND, "the sample's notify-in-transaction, deferred then run")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/notify-in-transaction")
                        .children(
                                appEvent(AppEventPayload.published(PLACED, 1)),
                                appEvent(AppEventPayload.listener(
                                        PLACED,
                                        "OrderListener#onPlaced",
                                        "AFTER_COMMIT",
                                        AppEventPayload.DEFERRED,
                                        null,
                                        -1)),
                                appEvent(AppEventPayload.listener(
                                        PLACED,
                                        "OrderListener#onPlaced",
                                        "AFTER_COMMIT",
                                        AppEventPayload.RAN,
                                        null,
                                        10)),
                                transactionAt("OrderService.notifyInTransaction", false, false, false, 0, 20 * MS))));
        fixtures.add(counterexample(TransactionalListenerSkipped.KIND, "an immediate listener that ran")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/notify")
                        .children(
                                appEvent(AppEventPayload.published(PLACED, 1)),
                                appEvent(AppEventPayload.listener(
                                        PLACED,
                                        "MailListener#onPlaced",
                                        AppEventPayload.IMMEDIATE,
                                        AppEventPayload.RAN,
                                        null,
                                        10)))));
    }

    // ---- after-commit-writes ------------------------------------------------------------------------------------

    private static void afterCommitWrites(List<Fixture> fixtures) {
        long start = 1_000 * MS;
        fixtures.add(positive(AfterCommitWrites.KIND, "the sample's archive: an AFTER_COMMIT write in no transaction")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/archive")
                        .children(
                                sqlAt("insert into audit values (?)", null, start + 18 * MS),
                                transactionAt("OrderService.archive", false, false, false, start, start + 20 * MS),
                                appEvent(AppEventPayload.listener(
                                                PLACED,
                                                "AuditListener#onArchived",
                                                "AFTER_COMMIT",
                                                AppEventPayload.RAN,
                                                null,
                                                start + 15 * MS))
                                        .took(10 * MS))));
        fixtures.add(counterexample(
                        AfterCommitWrites.KIND, "the sample's restore: an AFTER_COMMIT write in its own transaction")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/restore")
                        .children(
                                transactionAt("OrderService.restore", false, false, false, start, start + 20 * MS),
                                sqlAt("update audit set done = 1", null, start + 22 * MS),
                                transactionAt(
                                        "AuditService.record", false, false, false, start + 20 * MS, start + 23 * MS),
                                appEvent(AppEventPayload.listener(
                                                PLACED,
                                                "AuditListener#onRestored",
                                                "AFTER_COMMIT",
                                                AppEventPayload.RAN,
                                                null,
                                                start + 19 * MS))
                                        .took(6 * MS))));
        fixtures.add(counterexample(AfterCommitWrites.KIND, "an immediate listener's write inside the transaction")
                .seed(recording -> recording
                        .post("/api/insights/orders/{id}/ship")
                        .children(
                                sqlAt("insert into outbox values (?)", null, start + 3 * MS),
                                appEvent(AppEventPayload.listener(
                                                PLACED,
                                                "MailListener#onShipped",
                                                AppEventPayload.IMMEDIATE,
                                                AppEventPayload.RAN,
                                                null,
                                                start + MS))
                                        .took(5 * MS),
                                transactionAt("OrderService.ship", false, false, false, start, start + 20 * MS))));
    }

    // ---- orm-auto-flush and large-persistence-context -----------------------------------------------------------

    private static void ormObservations(List<Fixture> fixtures) {
        fixtures.add(positive(OrmAutoFlush.KIND, "the sample's save before each query")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        ormRequest(recording, "/api/insights/tags/auto-flush", orm(3, 6, 4 * MS, 12));
                    }
                }));
        fixtures.add(counterexample(OrmAutoFlush.KIND, "the sample's query first, then write, flushed at commit")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        ormRequest(recording, "/api/insights/tags/read-then-write", orm(0, 4, 0, 3));
                    }
                }));
        fixtures.add(counterexample(OrmAutoFlush.KIND, "one quick auto-flush in each of three requests")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        ormRequest(recording, "/api/tags/quick-flush", orm(1, 6, MS, 12));
                    }
                }));
        fixtures.add(positive(LargePersistenceContext.KIND, "1,200 entities in each of three requests")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        ormRequest(recording, "/api/report/all", orm(0, 3, 0, 1_200));
                    }
                }));
        fixtures.add(counterexample(LargePersistenceContext.KIND, "499 entities, just under the threshold")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        ormRequest(recording, "/api/report/page", orm(0, 3, 0, 499));
                    }
                }));
        fixtures.add(counterexample(LargePersistenceContext.KIND, "a large context in only two requests")
                .toleratingInsufficient()
                .seed(recording -> {
                    for (int i = 0; i < 2; i++) {
                        ormRequest(recording, "/api/report/twice", orm(0, 3, 0, 800));
                    }
                }));
    }

    private static void ormRequest(Recording recording, String path, OrmPayload orm) {
        long start = recording.clock();
        recording
                .post(path)
                .duration(100 * MS)
                .timing(new RequestTiming(start, -1, 2 * MS, 95 * MS))
                .children(new Ev(JournalSource.ORM, 80 * MS, orm));
    }

    /**
     * A session meter with one full flush.
     *
     * @param partialFlushes auto-flushes that wrote, together taking {@code partialFlushNanos}
     */
    private static OrmPayload orm(int partialFlushes, int statements, long partialFlushNanos, int entities) {
        return new OrmPayload(
                null,
                statements,
                statements * 5L * MS,
                1,
                MS,
                1,
                2 * MS,
                partialFlushes,
                partialFlushNanos,
                0,
                entities,
                0,
                0,
                0);
    }

    // ---- transaction-across-remote-call -------------------------------------------------------------------------

    private static void transactionAcrossRemoteCall(List<Fixture> fixtures) {
        Consumer<RuntimeInsightsService> pool = service -> service.setPoolSizes(name -> "db".equals(name) ? 10 : null);
        fixtures.add(positive(TransactionAcrossRemoteCall.KIND, "the sample's price check inside the transaction")
                .setup(pool)
                .seed(recording -> {
                    for (int i = 0; i < 4; i++) {
                        recording
                                .get("/api/insights/orders/{id}/price-check")
                                .duration(200 * MS)
                                .children(
                                        remoteCall("/prices", 80, 50),
                                        new Ev(
                                                JournalSource.CONNECTION,
                                                99 * MS,
                                                new ConnectionPayload("db", 0, 3, MS)),
                                        transactionAt("OrderService.priceCheck", false, false, false, 0, 100 * MS));
                    }
                }));
        fixtures.add(counterexample(
                        TransactionAcrossRemoteCall.KIND, "the sample's price check after the transaction committed")
                .setup(pool)
                .seed(recording -> {
                    for (int i = 0; i < 4; i++) {
                        recording
                                .get("/api/insights/orders/{id}/price-check-after-commit")
                                .duration(200 * MS)
                                .children(
                                        transactionAt("OrderService.priceCheck", false, false, false, 0, 100 * MS),
                                        remoteCall("/prices", 170, 50));
                    }
                }));
        fixtures.add(counterexample(TransactionAcrossRemoteCall.KIND, "calls inside the transaction all under 20 ms")
                .setup(pool)
                .seed(recording -> {
                    for (int i = 0; i < 4; i++) {
                        recording
                                .put("/api/stock")
                                .duration(200 * MS)
                                .children(
                                        remoteCall("/sync", 80, 5),
                                        transactionAt("StockService.update", false, false, false, 0, 100 * MS));
                    }
                }));
    }

    private static Ev remoteCall(String path, long completedMs, long durationMs) {
        return new Ev(
                JournalSource.REST_CLIENT,
                durationMs * MS,
                new RestClientPayload("GET", "stock:8080", path, 200, "RestClient", false, null, completedMs * MS));
    }

    // ---- lazy-sql-after-handler ---------------------------------------------------------------------------------

    private static void lazySqlAfterHandler(List<Fixture> fixtures) {
        fixtures.add(positive(LazySqlAfterHandler.KIND, "the sample's report: statements while the response is written")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/insights/orders/report")
                                .children(
                                        sqlAt("select * from orders where id = 1", RequestPhase.HANDLER, 150),
                                        transactionAt("OrderService.find", false, false, false, 100, 200),
                                        sqlAt("select * from lines where order_id = 1", RequestPhase.RESPONSE, 300));
                    }
                }));
        fixtures.add(counterexample(
                        LazySqlAfterHandler.KIND,
                        "statements while the response is written, in a transaction it opened")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/orders/{id}")
                                .children(
                                        sqlAt("select * from prices where id = 1", RequestPhase.RESPONSE, 350),
                                        transactionAt("Prices.load", false, false, false, 310, 400));
                    }
                }));
        fixtures.add(counterexample(
                        LazySqlAfterHandler.KIND, "a propagated task's statement while the response is written")
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/insights/after-response")
                                .children(
                                        sqlAt("select * from orders where id = 1", RequestPhase.HANDLER, 150),
                                        sqlAt("select * from audit where seen = 0", RequestPhase.RESPONSE, 300)
                                                .async("async-" + i));
                    }
                }));
        fixtures.add(counterexample(LazySqlAfterHandler.KIND, "the sample's line-by-line loop, all in the handler")
                .alsoFires(RepeatedSelects.KIND)
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        Ev[] loop = lineByLine(6);
                        for (int s = 0; s < loop.length; s++) {
                            SqlPayload statement = (SqlPayload) loop[s].payload();
                            loop[s] = sqlAt(statement.sql(), RequestPhase.HANDLER, 100 + s);
                        }
                        recording.get("/api/insights/orders").children(loop);
                    }
                }));
    }

    // ---- event-loop-blocking ------------------------------------------------------------------------------------

    private static void eventLoopBlocking(List<Fixture> fixtures) {
        fixtures.add(positive(EventLoopBlocking.KIND, "the WebFlux sample's JDBC on the event loop")
                .stack(InsightsStack.SPRING_WEBFLUX)
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/notes/blocking")
                                .children(sql("select * from notes").kind(ThreadKind.EVENT_LOOP));
                    }
                }));
        fixtures.add(counterexample(EventLoopBlocking.KIND, "the WebFlux sample's JDBC on boundedElastic")
                .stack(InsightsStack.SPRING_WEBFLUX)
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/notes/offloaded")
                                .children(sql("select * from notes").kind(ThreadKind.REACTOR_SCHEDULER));
                    }
                }));
        fixtures.add(counterexample(EventLoopBlocking.KIND, "an asynchronous client completing on an event loop")
                .stack(InsightsStack.SPRING_WEBFLUX)
                .seed(recording -> {
                    for (int i = 0; i < 3; i++) {
                        recording
                                .get("/api/notes/remote")
                                .children(new Ev(
                                                JournalSource.REST_CLIENT,
                                                5 * MS,
                                                new RestClientPayload(
                                                        "GET", "notes:8080", "/notes", 200, "WebClient", false))
                                        .kind(ThreadKind.EVENT_LOOP));
                    }
                }));
    }

    // ---- gc-inflated-latency and heap-growth-after-gc -----------------------------------------------------------

    private static final String YOUNG = "G1 Young Generation";
    private static final long MIB = 1024L * 1024L;

    private static void gcObservations(List<Fixture> fixtures) {
        fixtures.add(positive(GcInflatedLatency.KIND, "pauses completing during a route's slowest requests")
                .alsoFires(RouteTimeBreakdown.KIND)
                .seed(recording -> pausedRequests(recording, Set.of(2, 7, 9, 10))));
        fixtures.add(counterexample(GcInflatedLatency.KIND, "pauses spread evenly over a route's requests")
                .alsoFires(RouteTimeBreakdown.KIND)
                .seed(recording -> pausedRequests(recording, Set.of(1, 3, 8, 10))));
        fixtures.add(positive(HeapGrowthAfterGc.KIND, "old-generation occupancy rising after reclaiming collections")
                .seed(recording -> {
                    long[] collections = {0};
                    young(recording, collections, 30, 34);
                    reclaiming(recording, collections, 80, 40);
                    young(recording, collections, 40, 60);
                    reclaiming(recording, collections, 90, 45);
                    reclaiming(recording, collections, 95, 52);
                    young(recording, collections, 52, 70);
                    reclaiming(recording, collections, 100, 60);
                }));
        fixtures.add(counterexample(HeapGrowthAfterGc.KIND, "an old-generation level that holds")
                .seed(recording -> {
                    long[] collections = {0};
                    reclaiming(recording, collections, 80, 40);
                    reclaiming(recording, collections, 90, 41);
                    reclaiming(recording, collections, 85, 40);
                    reclaiming(recording, collections, 88, 42);
                }));
        fixtures.add(counterexample(HeapGrowthAfterGc.KIND, "young collections alone")
                .seed(recording -> {
                    long[] collections = {0};
                    young(recording, collections, 30, 34);
                    young(recording, collections, 34, 40);
                    young(recording, collections, 40, 46);
                }));
    }

    /** The route's cold request, then ten of 10 to 100 ms, pauses completing during those numbered {@code paused}. */
    private static void pausedRequests(Recording recording, Set<Integer> paused) {
        long collections = 0;
        recording.get("/api/report").duration(500 * MS).resources(resources(collections, 0));
        for (int i = 1; i <= 10; i++) {
            int pauses = paused.contains(i) ? 1 : 0;
            long after = collections;
            for (int p = 0; p < pauses; p++) {
                recording.unowned(new Ev(
                        JournalSource.GC,
                        5 * MS,
                        new GcPayload(YOUNG, ++collections, "end of minor GC", "G1 Evacuation Pause", true, 50, 20)));
            }
            recording.get("/api/report").duration(i * 10 * MS).resources(resources(after, pauses));
        }
    }

    private static ResourceUsage resources(long afterCollection, int pauses) {
        return new ResourceUsage(
                1,
                1,
                1,
                0,
                null,
                pauses,
                pauses == 0 ? List.of() : List.of(new GcPauseRange(YOUNG, afterCollection, afterCollection + pauses)),
                false);
    }

    private static void young(Recording recording, long[] collections, long oldBeforeMib, long oldAfterMib) {
        gc(recording, collections, oldAfterMib, oldBeforeMib, oldAfterMib);
    }

    private static void reclaiming(Recording recording, long[] collections, long oldBeforeMib, long oldAfterMib) {
        gc(recording, collections, oldAfterMib + 10, oldBeforeMib, oldAfterMib);
    }

    private static void gc(
            Recording recording, long[] collections, long heapAfterMib, long oldBeforeMib, long oldAfterMib) {
        recording.unowned(new Ev(
                JournalSource.GC,
                5 * MS,
                new GcPayload(
                        YOUNG,
                        ++collections[0],
                        "end of minor GC",
                        "G1 Evacuation Pause",
                        true,
                        200 * MIB,
                        heapAfterMib * MIB,
                        oldBeforeMib * MIB,
                        oldAfterMib * MIB)));
    }

    // ---- ai-usage-by-route --------------------------------------------------------------------------------------

    private static void aiUsageByRoute(List<Fixture> fixtures) {
        fixtures.add(positive(AiUsageByRoute.KIND, "an agent loop linked to its request by trace")
                .seed(recording -> {
                    recording
                            .get("/api/assistant")
                            .duration(200 * MS)
                            .trace("t1")
                            .children(
                                    traced(chat(1_000L, 100L, "stop"), 0),
                                    traced(new AiPayload(AiPayload.TOOL, null, null, null, null, null, false), 1),
                                    traced(chat(2_500L, 150L, "stop"), 2),
                                    traced(chat(4_000L, 4_096L, "length"), 3));
                    recording
                            .get("/api/assistant")
                            .duration(200 * MS)
                            .trace("t2")
                            .children(traced(chat(900L, 80L, "stop"), 0));
                }));
        fixtures.add(counterexample(AiUsageByRoute.KIND, "an AI span whose trace no retained request carries")
                .seed(recording -> {
                    recording.get("/api/orders").duration(200 * MS).trace("t3");
                    recording.unowned(new Ev(JournalSource.AI, 40 * MS, chat(10L, 10L, "stop")).trace("t-unknown"));
                }));
    }

    private static AiPayload chat(Long input, Long output, String finishReason) {
        return new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", input, output, finishReason, false);
    }

    /** An AI span linked to its request only by its trace id and its time inside it. */
    private static Ev traced(AiPayload payload, int order) {
        return new Ev(JournalSource.AI, 40 * MS, payload).at(10 + order).traceOnly();
    }

    // ---- framework-warnings-by-route ----------------------------------------------------------------------------

    private static void frameworkWarningsByRoute(List<Fixture> fixtures) {
        String template = "HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory";
        fixtures.add(positive(FrameworkWarningsByRoute.KIND, "Hibernate's in-memory pagination warning on a route")
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .children(
                                log("org.hibernate.orm.query", "WARN", template),
                                log("org.hibernate.orm.query", "WARN", template))));
        fixtures.add(counterexample(FrameworkWarningsByRoute.KIND, "an application logger's warning")
                .seed(recording -> recording
                        .get("/api/orders/{id}")
                        .children(log("com.example.Orders", "WARN", "Slow order {}"))));
        fixtures.add(
                counterexample(FrameworkWarningsByRoute.KIND, "a framework warning logged at startup, by no request")
                        .seed(recording -> {
                            recording.unowned(
                                    log("org.hibernate.orm.deprecation", "WARN", "HHH90000025: dialect is deprecated"));
                            recording.get("/api/orders/{id}");
                        }));
        fixtures.add(counterexample(
                        FrameworkWarningsByRoute.KIND,
                        "a container's ERROR on a failed request's thread, logged once its filters returned")
                .alsoFires(ExceptionHotspots.KIND)
                .seed(recording -> {
                    recording
                            .get("/api/orders/{id}")
                            .status(500)
                            .children(exception("boom", "java.lang.IllegalStateException"));
                    // Its filters, and so its id, are gone: Tomcat logs the failure it answered 500 for 10 ms later.
                    recording.unowned(log(
                                    "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]",
                                    "ERROR",
                                    "Servlet.service() threw exception")
                            .at(40)
                            .onThread("http-1"));
                }));
    }

    private static Ev log(String logger, String level, String template) {
        return new Ev(JournalSource.LOG, 0, new LogPayload(logger, level, template, null));
    }

    // ---- work-after-response ------------------------------------------------------------------------------------

    private static void workAfterResponse(List<Fixture> fixtures) {
        Consumer<RuntimeInsightsService> agent = service -> service.setAgent(() -> null, null);
        fixtures.add(positive(WorkAfterResponse.KIND, "the sample's query handed to a raw executor")
                .setup(agent)
                .seed(recording -> {
                    long start = recording.nextStart();
                    recording
                            .get("/seed/work-after-response")
                            .thread("http-nio-1")
                            .children(
                                    handoff(start + 10, 200, null).late(),
                                    new Ev(
                                                    JournalSource.SQL,
                                                    2 * MS,
                                                    new SqlPayload(
                                                            "select * from orders", "Seeds.lambda:12", "db", false))
                                            .at(150)
                                            .async("async-1")
                                            .onThread("pool-1-thread-1")
                                            .late());
                }));
        fixtures.add(counterexample(WorkAfterResponse.KIND, "the sample's handler that waits for its task")
                .setup(agent)
                .seed(recording -> {
                    long start = recording.nextStart();
                    recording
                            .get("/seed/work-after-response/waits")
                            .thread("http-nio-1")
                            .children(
                                    new Ev(
                                                    JournalSource.SQL,
                                                    2 * MS,
                                                    new SqlPayload(
                                                            "select * from orders", "Seeds.lambda:12", "db", false))
                                            .at(5)
                                            .async("async-1")
                                            .onThread("pool-1-thread-1"),
                                    handoff(start + 2, 10, false));
                }));
    }

    private static Ev handoff(long startMillis, long durationMillis, Boolean afterResponse) {
        return new Ev(
                        JournalSource.AGENT_EXECUTORS,
                        Duration.ofMillis(durationMillis).toNanos(),
                        new AsyncHandoffPayload(
                                "async-1",
                                null,
                                "com.example.Seeds$$Lambda",
                                "ThreadPoolExecutor.runWorker",
                                startMillis - 1,
                                1_000_000,
                                2_048L,
                                false,
                                null,
                                afterResponse,
                                afterResponse == null ? null : afterResponse ? 100_000L : 0L,
                                false))
                .atEpoch(startMillis)
                .async("async-1")
                .onThread("pool-1-thread-1");
    }

    // ---- changed-code-not-executed ------------------------------------------------------------------------------

    private static void changedCodeNotExecuted(List<Fixture> fixtures) {
        fixtures.add(positive(ChangedCodeNotExecuted.KIND, "a changed method that never ran")
                .setup(service -> service.setCodeInventory(() -> changes(
                        method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED),
                        method("list", CodeChanges.CHANGED, CodeInventoryService.EXECUTED))))
                .seed(recording -> {}));
        fixtures.add(counterexample(ChangedCodeNotExecuted.KIND, "a changed method that ran")
                .setup(service -> service.setCodeInventory(
                        () -> changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.EXECUTED))))
                .seed(recording -> {}));
        fixtures.add(counterexample(ChangedCodeNotExecuted.KIND, "a changed method the agent could not track")
                .setup(service -> service.setCodeInventory(
                        () -> changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.NOT_TRACKED))))
                .seed(recording -> {}));
    }

    private static CodeInventoryMethodDto method(String name, String change, String status) {
        return new CodeInventoryMethodDto(
                "shop.OrderService#" + name + "()V",
                "shop",
                "shop.OrderService",
                name,
                "()V",
                status,
                CodeInventoryService.NOT_TRACKED.equals(status) ? "transform failed" : null,
                change,
                CodeInventoryService.EXECUTED.equals(status) ? "00000000000000ab" : null,
                CodeInventoryService.EXECUTED.equals(status) ? "GET /orders" : null,
                null);
    }

    private static ChangedCode changes(CodeInventoryMethodDto... methods) {
        return new ChangedCode(
                null,
                true,
                null,
                List.of(new ChangedClass("shop.OrderService", List.of(methods), List.of("GET /orders"))),
                methods.length,
                "COMPLETE",
                null);
    }

    // ---- common children ----------------------------------------------------------------------------------------

    private static Ev sql(String statement) {
        return sql(new SqlPayload(statement, "Repo.run:1", "db", false));
    }

    private static Ev sql(SqlPayload payload) {
        return new Ev(JournalSource.SQL, MS, payload);
    }

    private static Ev sqlAt(String statement, RequestPhase phase, long completedNanos) {
        return sql(new SqlPayload(statement, "Repo.run:1", "db", false, null, phase, completedNanos));
    }

    private static Ev transaction(TransactionPayload payload) {
        return new Ev(JournalSource.TRANSACTION, MS, payload);
    }

    private static Ev transactionAt(
            String method, boolean nested, boolean savepoint, boolean rolledBack, long startNanos, long endNanos) {
        return new Ev(
                JournalSource.TRANSACTION,
                endNanos - startNanos,
                new TransactionPayload(method, rolledBack, nested, savepoint, startNanos));
    }

    private static Ev exception(String signature, String className) {
        return new Ev(JournalSource.EXCEPTION, MS, new ExceptionPayload("g-" + signature, className, signature));
    }

    private static Ev appEvent(AppEventPayload payload) {
        return new Ev(JournalSource.APP_EVENT, MS, payload);
    }

    // ---- the recording DSL --------------------------------------------------------------------------------------

    static Builder positive(String kind, String name) {
        return new Builder(kind, Role.POSITIVE, name);
    }

    static Builder counterexample(String kind, String name) {
        return new Builder(kind, Role.COUNTEREXAMPLE, name);
    }

    /** Builds one fixture. */
    static final class Builder {

        private final String kind;
        private final Role role;
        private final String name;
        private InsightsStack stack = InsightsStack.SPRING_MVC;
        private Set<String> alsoFires = Set.of();
        private Predicate<RuntimeObservationDto> tolerated = row -> false;
        private Consumer<RuntimeInsightsService> setup = service -> {};

        private Builder(String kind, Role role, String name) {
            this.kind = kind;
            this.role = role;
            this.name = name;
        }

        Builder stack(InsightsStack stack) {
            this.stack = stack;
            return this;
        }

        Builder alsoFires(String... kinds) {
            this.alsoFires = Set.of(kinds);
            return this;
        }

        Builder tolerating(Predicate<RuntimeObservationDto> tolerated) {
            this.tolerated = tolerated;
            return this;
        }

        /** A counterexample that may still show its kind's insufficient row, never an observed one. */
        Builder toleratingInsufficient() {
            return tolerating(row -> "INSUFFICIENT".equals(row.status()));
        }

        Builder setup(Consumer<RuntimeInsightsService> setup) {
            this.setup = setup;
            return this;
        }

        Fixture seed(Consumer<Recording> seed) {
            Recording recording = new Recording();
            seed.accept(recording);
            return new Fixture(kind, role, name, stack, alsoFires, tolerated, setup, recording.events());
        }
    }

    /**
     * A child event of a request or an execution.
     *
     * @param offsetMillis when it started after its request started, by the wall clock, unless {@code absolute}
     * @param absolute whether {@code offsetMillis} is an absolute epoch millisecond
     * @param linkedByTraceOnly whether it carries only its request's trace id, as an AI span does
     */
    record Ev(
            JournalSource source,
            long nanos,
            RuntimeEventPayload payload,
            String thread,
            ThreadKind threadKind,
            String executionId,
            long offsetMillis,
            boolean absolute,
            boolean afterResponse,
            boolean linkedByTraceOnly,
            String traceId) {

        Ev(JournalSource source, long nanos, RuntimeEventPayload payload) {
            this(source, nanos, payload, null, null, null, 1, false, false, false, null);
        }

        Ev took(long durationNanos) {
            return new Ev(
                    source,
                    durationNanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev onThread(String name) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    name,
                    threadKind,
                    executionId,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev kind(ThreadKind kind) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    kind,
                    executionId,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev async(String id) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    id,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev at(long offset) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    offset,
                    false,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev atEpoch(long epochMillis) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    epochMillis,
                    true,
                    afterResponse,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev late() {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    offsetMillis,
                    absolute,
                    true,
                    linkedByTraceOnly,
                    traceId);
        }

        Ev traceOnly() {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    true,
                    traceId);
        }

        Ev trace(String id) {
            return new Ev(
                    source,
                    nanos,
                    payload,
                    thread,
                    threadKind,
                    executionId,
                    offsetMillis,
                    absolute,
                    afterResponse,
                    linkedByTraceOnly,
                    id);
        }
    }

    /** Records requests, executions, and unowned events in the order the recorders publish them. */
    static final class Recording {

        private final List<RuntimeEvent> events = new ArrayList<>();
        private final List<Request> pending = new ArrayList<>();
        private int units;
        private long clock = 1_000 * MS;
        private boolean together;

        /** The epoch millisecond the next request starts at; each starts one second after the previous one. */
        long nextStart() {
            flush();
            return startOf(units + 1);
        }

        /** The monotonic nanosecond the next request starts at. */
        long clock() {
            flush();
            return clock;
        }

        Request get(String template) {
            return request("GET", template, template.replace("{id}", "42"));
        }

        Request get(String template, String path) {
            return request("GET", template, path);
        }

        Request post(String template) {
            return request("POST", template, template.replace("{id}", "42"));
        }

        Request put(String template) {
            return request("PUT", template, template.replace("{id}", "42"));
        }

        /**
         * Records the requests {@code requests} makes as running at the same time: each starts a millisecond after the
         * previous one, their children are published in turn, one of each, and their responses after all of them.
         */
        void concurrently(Runnable requests) {
            flush();
            together = true;
            try {
                requests.run();
            } finally {
                together = false;
            }
            long start = startOf(pending.get(0).number);
            for (int i = 0; i < pending.size(); i++) {
                pending.get(i).start = start + i;
            }
            int most = pending.stream()
                    .mapToInt(request -> request.children.size())
                    .max()
                    .orElse(0);
            for (int child = 0; child < most; child++) {
                for (Request request : pending) {
                    if (child < request.children.size()
                            && !request.children.get(child).afterResponse()) {
                        events.add(request.event(request.children.get(child)));
                    }
                }
            }
            for (Request request : pending) {
                events.add(request.anchor());
            }
            for (Request request : pending) {
                for (Ev child : request.children) {
                    if (child.afterResponse()) {
                        events.add(request.event(child));
                    }
                }
            }
            pending.clear();
        }

        private Request request(String method, String template, String path) {
            if (!together) {
                flush();
            }
            Request request = new Request(++units, method, template, path);
            clock += 1_000 * MS;
            pending.add(request);
            return request;
        }

        /** A scheduled run of {@code task}, its children recorded before it. */
        void scheduled(String task, Ev... children) {
            execution(JournalSource.SCHEDULED, new ScheduledPayload(task, null), 0, children);
        }

        /**
         * A message consumed from {@code destination}, its children recorded before it. Its start is derived from its
         * end and duration in whole milliseconds, as the messaging recorders do, so it reads a millisecond after the
         * first child it ran.
         */
        void consumed(String destination, Ev... children) {
            execution(JournalSource.MESSAGING, new MessagingPayload("kafka", false, destination, false), 1, children);
        }

        private void execution(JournalSource source, RuntimeEventPayload anchor, long anchorOffset, Ev... children) {
            flush();
            String executionId = "x" + (++units);
            long start = startOf(units);
            for (Ev child : children) {
                events.add(new RuntimeEvent(
                        child.source(),
                        start + child.offsetMillis(),
                        child.nanos(),
                        null,
                        executionId,
                        null,
                        "worker-1",
                        child.threadKind(),
                        failed(child),
                        child.payload()));
            }
            events.add(new RuntimeEvent(
                    source, start + anchorOffset, 20 * MS, null, executionId, null, "worker-1", null, false, anchor));
        }

        /** An event no request or execution owns, such as a collection or a log written at startup. */
        void unowned(Ev event) {
            flush();
            events.add(new RuntimeEvent(
                    event.source(),
                    startOf(units) + event.offsetMillis(),
                    event.nanos(),
                    null,
                    null,
                    event.traceId(),
                    event.thread(),
                    event.threadKind(),
                    failed(event),
                    event.payload()));
        }

        List<RuntimeEvent> events() {
            flush();
            return List.copyOf(events);
        }

        private void flush() {
            for (Request request : pending) {
                request.publish(events);
            }
            pending.clear();
        }

        private static long startOf(int unit) {
            return 10_000L + unit * 1_000L;
        }

        /**
         * Whether a recorder marks this event failed, so the journal keeps it in its reserved share longer than its
         * routine siblings: an exception, a failed statement or call, a rolled-back transaction, a failed attempt, or one
         * that took a second or more.
         */
        static boolean failed(Ev event) {
            if (event.nanos() >= SLOW) {
                return true;
            }
            RuntimeEventPayload payload = event.payload();
            if (payload instanceof ExceptionPayload) {
                return true;
            }
            if (payload instanceof SqlPayload sql) {
                return sql.failed();
            }
            if (payload instanceof TransactionPayload transaction) {
                return transaction.rolledBack();
            }
            if (payload instanceof RestClientPayload call) {
                return call.failed();
            }
            if (payload instanceof AiPayload ai) {
                return ai.failed();
            }
            return payload instanceof FaultTolerancePayload policy && policy.failure();
        }
    }

    /** One HTTP request, published when the recording moves on. */
    static final class Request {

        private final int number;
        private final String method;
        private final String template;
        private final String path;
        private int status = 200;
        private long durationNanos = 30 * MS;
        private RequestTiming timing;
        private ResourceUsage resources;
        private String traceId;
        private String thread = "http-1";
        private final List<Ev> children = new ArrayList<>();

        private long start;

        private Request(int number, String method, String template, String path) {
            this.number = number;
            this.method = method;
            this.template = template;
            this.path = path;
            this.start = Recording.startOf(number);
        }

        Request status(int status) {
            this.status = status;
            return this;
        }

        Request duration(long nanos) {
            this.durationNanos = nanos;
            return this;
        }

        Request timing(RequestTiming timing) {
            this.timing = timing;
            return this;
        }

        Request resources(ResourceUsage resources) {
            this.resources = resources;
            return this;
        }

        Request trace(String traceId) {
            this.traceId = traceId;
            return this;
        }

        Request thread(String thread) {
            this.thread = thread;
            return this;
        }

        Request children(Ev... events) {
            children.addAll(List.of(events));
            return this;
        }

        private void publish(List<RuntimeEvent> events) {
            List<RuntimeEvent> late = new ArrayList<>();
            for (Ev child : children) {
                (child.afterResponse() ? late : events).add(event(child));
            }
            events.add(anchor());
            events.addAll(late);
        }

        private RuntimeEvent event(Ev child) {
            String requestId = "r" + number;
            return new RuntimeEvent(
                    child.source(),
                    child.absolute() ? child.offsetMillis() : start + child.offsetMillis(),
                    child.nanos(),
                    child.linkedByTraceOnly() ? null : requestId,
                    child.linkedByTraceOnly() ? null : child.executionId(),
                    child.linkedByTraceOnly() ? traceId : null,
                    child.thread() != null ? child.thread() : thread,
                    child.threadKind(),
                    Recording.failed(child),
                    child.payload());
        }

        private RuntimeEvent anchor() {
            return new RuntimeEvent(
                    JournalSource.HTTP,
                    start,
                    durationNanos,
                    "r" + number,
                    null,
                    traceId,
                    thread,
                    null,
                    status >= 500 || durationNanos >= SLOW,
                    new HttpPayload(method, path, template, null, status, resources, timing));
        }
    }
}
