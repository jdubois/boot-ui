package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionPlacementObservationsTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aFailedBeginDoesNotEstablishATransactionWindow() {
        TransactionPayload failed = new TransactionPayload(
                "Failed.begin", false, false, false, 100, false, null, false, "BeginException", -1, -1, -1, false);
        RuntimeEvent event = RuntimeEvent.of(
                JournalSource.TRANSACTION,
                1_000,
                100,
                CorrelationContext.forRequest("failed"),
                "main",
                null,
                true,
                failed);
        ProjectedRequest request = new ProjectedRequest(
                "failed",
                "POST /failed",
                "POST",
                "/failed",
                200,
                1_000,
                200,
                List.of(event),
                null,
                null,
                null,
                "main",
                ProjectedRequest.Kind.HTTP,
                false);
        TransactionWindows windows = new TransactionWindows(request);
        assertThat(windows.windows()).isEmpty();
        assertThat(windows.first("main")).isNull();
        assertThat(windows.of(event)).isNull();
        assertThat(windows.complete()).isTrue();
    }

    @Test
    void writesAcrossIndependentBoundaryCandidatesAreReportedAndJoinedOrRolledBackOnesAreNot() {
        // A transaction, then an autocommit write after it.
        request(
                "POST",
                "/api/orders",
                transaction("OrderService.place", false, false, false, 100, 200),
                sql("insert into orders values (1)", RequestPhase.HANDLER, 150),
                sql("insert into audit values (1)", RequestPhase.HANDLER, 250));
        // A REQUIRES_NEW transaction nested inside the outer one.
        request(
                "POST",
                "/api/orders",
                transaction("AuditService.log", true, false, false, 130, 140),
                transaction("OrderService.place", false, false, false, 100, 200),
                sql("insert into orders values (2)", RequestPhase.HANDLER, 120),
                sql("insert into audit values (2)", RequestPhase.HANDLER, 135),
                sql("insert into lines values (2)", RequestPhase.HANDLER, 150));
        // Counterexample: a NESTED savepoint commits with the outer transaction.
        request(
                "POST",
                "/api/orders",
                transaction("StockService.reserve", true, true, false, 130, 140),
                transaction("OrderService.place", false, false, false, 100, 200),
                sql("update stock set n = 1", RequestPhase.HANDLER, 135),
                sql("insert into orders values (3)", RequestPhase.HANDLER, 150));
        // Counterexample: the transaction rolled back, so only the autocommit write committed.
        request(
                "POST",
                "/api/orders",
                transaction("OrderService.place", false, false, true, 100, 200),
                sql("insert into orders values (4)", RequestPhase.HANDLER, 150),
                sql("insert into audit values (4)", RequestPhase.HANDLER, 250));
        request("GET", "/api/orders", sql("select * from orders", RequestPhase.HANDLER, 150));

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();

        assertThat(byKind(report, SplitTransactionWrites.KIND)).singleElement().satisfies(observation -> {
            assertThat(observation.sentence())
                    .isEqualTo("`POST /api/orders` recorded writes in 2 groups across data sources or recorded"
                            + " transaction boundaries in 2 of 4 requests that wrote; their required atomicity"
                            + " needs verification.");
            assertThat(observation.exemplarRequestIds()).containsExactly("r1", "r2");
        });
        assertThat(service(InsightsStack.SPRING_MVC)
                        .insight(RuntimeInsightsService.idOf(SplitTransactionWrites.KIND, "POST /api/orders"))
                        .rows()
                        .get(1)
                        .cells()
                        .get(2))
                .contains(
                        "`db`, recorded physical boundary",
                        ": 2 writes, first `insert into orders values (?)`",
                        ": 1 write, first `insert into audit values (?)`")
                .doesNotContain("OrderService.place", "AuditService.log", "committed");
        assertThat(check(service(InsightsStack.QUARKUS).report(), SplitTransactionWrites.KIND)
                        .status())
                .isEqualTo("NOT_APPLICABLE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void writesToTwoNamedSourcesInsideOneWindowAskAboutAtomicityWithoutInventingResourceOwnership(
            boolean boundaryKnown) {
        request(
                "POST",
                "/api/resources",
                new Child(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload(
                                "OrderService.place",
                                false,
                                false,
                                false,
                                100,
                                false,
                                null,
                                false,
                                null,
                                -1,
                                -1,
                                -1,
                                true,
                                boundaryKnown),
                        null),
                write("ordersDataSource", 140),
                write("auditDataSource", 150));

        for (InsightsStack stack : List.of(InsightsStack.SPRING_MVC, InsightsStack.SPRING_WEBFLUX)) {
            RuntimeInsightsService insights = service(stack);
            assertThat(check(insights.report(), SplitTransactionWrites.KIND).title())
                    .isEqualTo("Write atomicity to verify");
            assertThat(byKind(insights.report(), SplitTransactionWrites.KIND))
                    .singleElement()
                    .satisfies(observation -> {
                        assertThat(observation.sentence())
                                .contains("data sources", "atomicity")
                                .doesNotContain("2 independent units", "committed its writes", "one may persist");
                        assertThat(observation.eligible()).isEqualTo(1);
                        assertThat(observation.affected()).isEqualTo(1);
                        assertThat(insights.insight(observation.id()).rows())
                                .singleElement()
                                .satisfies(row -> assertThat(String.join(" ", row.cells()))
                                        .contains("ordersDataSource", "auditDataSource")
                                        .doesNotContain("autocommit", "OrderService.place"));
                        assertThat(observation.limitations())
                                .anyMatch(limit -> limit.contains("XA") && limit.contains("ownership"));
                    });
        }
    }

    @Test
    void oneKnownSourceOrAnUnknownSourceDoesNotInventAMultiResourceBoundary() {
        request(
                "POST",
                "/api/one-resource",
                transaction("OrderService.place", false, false, false, 100, 200),
                write("ordersDataSource", 140),
                write("ordersDataSource", 150));
        request(
                "POST",
                "/api/unknown-resource",
                transaction("OrderService.place", false, false, false, 100, 200),
                write("ordersDataSource", 140),
                write(null, 150));

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();

        assertThat(byKind(report, SplitTransactionWrites.KIND)).isEmpty();
        assertThat(check(report, SplitTransactionWrites.KIND).eligibleRequests())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rolledBackOrSavepointWindowsDoNotProveTheOwnershipOfTwoNamedSources(boolean rolledBack) {
        request(
                "POST",
                "/api/resources",
                transaction("OrderService.place", false, false, rolledBack, 100, 200),
                transaction("StockService.reserve", true, true, false, 130, 170),
                write("ordersDataSource", 140),
                write("auditDataSource", 150));

        assertThat(byKind(service(InsightsStack.SPRING_MVC).report(), SplitTransactionWrites.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence())
                        .contains("atomicity")
                        .doesNotContain("2 independent units", "committed its writes", "one may persist"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-window", "unknown-window-time", "unknown-statement-time"})
    void namedResourceFactsDoNotRequireInventedTransactionPlacement(String missing) {
        if (!missing.equals("no-window")) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.TRANSACTION,
                    1_000,
                    100,
                    CorrelationContext.forRequest("r1"),
                    "http-1",
                    null,
                    false,
                    new TransactionPayload(
                            "OrderService.place",
                            false,
                            false,
                            false,
                            missing.equals("unknown-window-time") ? -1 : 100)));
        }
        request(
                "POST",
                "/api/resources",
                write("ordersDataSource", 140),
                write("auditDataSource", missing.equals("unknown-statement-time") ? -1 : 150));

        assertThat(byKind(service(InsightsStack.SPRING_MVC).report(), SplitTransactionWrites.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.eligible()).isEqualTo(1);
                    assertThat(observation.affected()).isEqualTo(1);
                    assertThat(observation.sentence()).contains("atomicity");
                    assertThat(observation.limitations())
                            .anyMatch(limit -> limit.contains("not proven physical-resource"));
                });
    }

    @Test
    void anUnprovenBoundaryIsNotAutocommitOrAnEligibleAbsenceOfSplitWrites() {
        request(
                "POST",
                "/api/unknown-boundary",
                new Child(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload(
                                "OrderService.place",
                                false,
                                false,
                                false,
                                100,
                                false,
                                null,
                                false,
                                null,
                                -1,
                                -1,
                                -1,
                                true,
                                false),
                        null),
                write("ordersDataSource", 140),
                write("ordersDataSource", 150));

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();

        assertThat(byKind(report, SplitTransactionWrites.KIND)).isEmpty();
        assertThat(check(report, SplitTransactionWrites.KIND).eligibleRequests())
                .isZero();
        assertThat(check(report, SplitTransactionWrites.KIND).reason()).contains("boundary ownership");
    }

    @Test
    void mixedSourcesAndKnownSplitBoundariesKeepOneRouteIdentityAndRetainedFacts() {
        request(
                "POST",
                "/api/resources",
                transaction("OrderService.place", false, false, false, 100, 200),
                write("ordersDataSource", 140),
                write("auditDataSource", 150));
        request(
                "POST",
                "/api/resources",
                transaction("OrderService.place", false, false, false, 100, 200),
                write("ordersDataSource", 140),
                write("ordersDataSource", 250));
        request(
                "POST",
                "/api/resources",
                transaction("OrderService.place", false, false, false, 100, 200),
                write("ordersDataSource", 140),
                write("ordersDataSource", 150));
        request("POST", "/api/resources", write("ordersDataSource", -1));
        RuntimeInsightsService insights = service(InsightsStack.SPRING_MVC);
        RuntimeObservationDto before =
                byKind(insights.report(), SplitTransactionWrites.KIND).get(0);
        long sequence = journal.status().lastSequence();
        insights.setSqlCapture(() -> {
            throw new IllegalStateException("controlled current capture-status failure");
        });

        assertThat(byKind(insights.report(), SplitTransactionWrites.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.id()).isEqualTo(before.id());
                    assertThat(observation.id())
                            .isEqualTo(RuntimeInsightsService.idOf(SplitTransactionWrites.KIND, "POST /api/resources"));
                    assertThat(observation.eligible()).isEqualTo(3);
                    assertThat(observation.affected()).isEqualTo(2);
                    assertThat(observation.exemplarRequestIds()).containsExactly("r1", "r2");
                    assertThat(insights.insight(observation.id()).rows()).hasSize(2);
                });
        assertThat(journal.status().lastSequence()).isEqualTo(sequence);
    }

    @Test
    void nativeExecutedWritesKeepTheirDataSourcesEvenWhenOneTransactionWindowContainsBoth() throws Exception {
        SqlTraceRecorder sqlRecorder = new SqlTraceRecorder(true, true, false, false, 20, 100, 2_000, 200, 5);
        sqlRecorder.setRuntimeEventSink(journal);
        TransactionRecorder transactions = new TransactionRecorder(true, true, 20, 100, 100, sqlRecorder);
        transactions.setRuntimeEventSink(journal);
        List<DataSource> sources = new ArrayList<>();
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            long boundary = transactions.beginTransaction(
                    "OrderService.place", false, null, Thread.currentThread().getName(), null);
            for (String name : List.of("ordersDataSource", "auditDataSource")) {
                DataSource source = mock(DataSource.class);
                sources.add(source);
                Connection connection = mock(Connection.class);
                Statement statement = mock(Statement.class);
                when(source.getConnection()).thenReturn(connection);
                when(connection.createStatement()).thenReturn(statement);
                when(statement.executeUpdate("insert into audit values (1)")).thenReturn(1);
                try (Connection traced =
                        SqlTracingProxies.wrapNamed(source, sqlRecorder, name).getConnection()) {
                    traced.createStatement().executeUpdate("insert into audit values (1)");
                }
            }
            transactions.completeTransaction(boundary, TransactionRecorder.Status.COMMITTED, null);
        }
        request("POST", "/api/native-resources");
        List<RuntimeEvent> events =
                journal.entries().stream().map(entry -> entry.event()).toList();
        List<RuntimeEvent> writes =
                events.stream().filter(SplitTransactionWrites::committedWrite).toList();
        RuntimeEvent boundary = events.stream()
                .filter(event -> event.payload() instanceof TransactionPayload)
                .findFirst()
                .orElseThrow();
        assertThat(events.stream().filter(event -> event.payload() instanceof TransactionPayload))
                .hasSize(1);
        assertThat(writes).hasSize(2).allSatisfy(event -> {
            SqlPayload sql = (SqlPayload) event.payload();
            TransactionPayload transaction = (TransactionPayload) boundary.payload();
            assertThat(sql.executed()).isTrue();
            assertThat(sql.completedNanos())
                    .isBetween(transaction.startNanos(), transaction.startNanos() + boundary.durationNanos());
        });
        assertThat(writes.stream().map(event -> ((SqlPayload) event.payload()).dataSource()))
                .containsExactlyInAnyOrder("ordersDataSource", "auditDataSource");
        assertThat(byKind(service(InsightsStack.SPRING_MVC).report(), SplitTransactionWrites.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence())
                        .contains("data sources", "atomicity")
                        .doesNotContain("independent units", "committed its writes"));
        for (DataSource source : sources) {
            verify(source).getConnection();
            verifyNoMoreInteractions(source);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/* Hibernate insert */ insert into orders values (?)",
                "-- Hibernate update\nupdate orders set shipped = ?",
                "/* Hibernate delete */ delete from orders where id = ?",
                "/* Hibernate merge */ merge into orders using incoming on orders.id = incoming.id when matched then"
                        + " update set shipped = ?",
                "replace into orders values (?)",
                "/* Hibernate replace */ replace into orders values (?)"
            })
    void commentedWritesAndReplaceKeepTheirTransactionUnitsWhileCommentedReadsDoNotWrite(String statement) {
        request(
                "POST",
                "/api/orders",
                transaction("OrderService.place", false, false, false, 100, 200),
                sql(statement, RequestPhase.HANDLER, 150),
                sql("insert into audit values (?)", RequestPhase.HANDLER, 250),
                sql("/* delete from orders */ select * from orders", RequestPhase.HANDLER, 260),
                sql("-- update orders set shipped = ?\nselect * from orders", RequestPhase.HANDLER, 270));
        request(
                "GET",
                "/api/read-only",
                sql("/* insert into orders values (?) */ select * from orders", RequestPhase.HANDLER, 150),
                sql("select 'delete from orders' from orders", RequestPhase.HANDLER, 250));

        for (InsightsStack stack : List.of(InsightsStack.SPRING_MVC, InsightsStack.SPRING_WEBFLUX)) {
            RuntimeInsightsService insights = service(stack);
            assertThat(byKind(insights.report(), SplitTransactionWrites.KIND))
                    .singleElement()
                    .satisfies(observation -> {
                        assertThat(observation.subject()).isEqualTo("POST /api/orders");
                        assertThat(observation.sentence())
                                .contains("recorded writes in 2 groups", "in 1 of 1 request", "atomicity");
                        assertThat(observation.exemplarRequestIds()).containsExactly("r1");
                        assertThat(insights.insight(observation.id()).rows())
                                .singleElement()
                                .satisfies(row -> {
                                    assertThat(row.cells().subList(0, 2)).containsExactly("r1", "2");
                                    assertThat(row.cells().get(2))
                                            .contains("recorded physical boundary", "autocommit candidate")
                                            .doesNotContain("OrderService.place");
                                });
                    });
        }
    }

    @Test
    void sqlRunAfterTheHandlerOutsideATransactionIsReportedFromThreeRequests() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    transaction("OrderService.find", false, false, false, 100, 200),
                    sql("select * from orders where id = 1", RequestPhase.HANDLER, 150),
                    sql("select * from lines where order_id = 1", RequestPhase.RESPONSE, 300),
                    // Counterexample: in the response phase, but inside a transaction the view opened.
                    transaction("Prices.load", false, false, false, 310, 400),
                    sql("select * from prices where id = 1", RequestPhase.RESPONSE, 350));
        }
        request(
                "GET",
                "/api/customers/{id}",
                sql("select * from notes where customer_id = 1", RequestPhase.RESPONSE, 300),
                sql("select * from notes where customer_id = 2", RequestPhase.RESPONSE, 310));

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();
        List<RuntimeObservationDto> lazy = byKind(report, LazySqlAfterHandler.KIND);

        assertThat(lazy).hasSize(2);
        assertThat(lazy.get(0).status()).isEqualTo("OBSERVED");
        assertThat(lazy.get(0).sentence())
                .isEqualTo("`GET /api/orders/{id}` ran `select * from lines where order_id = ?` after its handler"
                        + " returned, 3 times in 3 of 3 requests, outside a transaction, while the response was"
                        + " written.");
        assertThat(lazy.get(1).status()).isEqualTo("INSUFFICIENT");
        assertThat(lazy.get(1).subject()).isEqualTo("GET /api/customers/{id}");
        assertThat(check(service(InsightsStack.SPRING_WEBFLUX).report(), LazySqlAfterHandler.KIND)
                        .status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(check(service(InsightsStack.QUARKUS).report(), LazySqlAfterHandler.KIND)
                        .reason())
                .contains("Exception hotspots");
    }

    @Test
    void requestsWhoseResponseSqlCannotBePlacedAgainstTheirTransactionsAreCountedApart() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    transaction("OrderService.find", false, false, false, 100, 200),
                    sql("select * from lines where order_id = 1", RequestPhase.RESPONSE, 300));
        }
        // The transaction has no monotonic start, so whether the statement ran inside it cannot be told.
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.TRANSACTION,
                        100,
                        new TransactionPayload("OrderService.find", false, false, false, -1),
                        null),
                sql("select * from lines where order_id = 1", RequestPhase.RESPONSE, 300));

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();
        RuntimeObservationDto lazy =
                byKind(report, LazySqlAfterHandler.KIND).stream().findFirst().orElseThrow();

        assertThat(lazy.sentence()).contains("in 3 of 3 requests");
        assertThat(lazy.limitations())
                .contains("1 request could not be placed, since a transaction or statement had no monotonic time.");
        assertThat(check(report, LazySqlAfterHandler.KIND).reason())
                .contains("1 request ran SQL after the handler that could not be placed against its transactions");
    }

    @Test
    void sqlAViewRanWhileRenderingIsNamedAsViewRenderingNotLazyLoading() {
        ApplicationFrames formatter = ApplicationFrames.of(List.of(
                "org.springframework.samples.petclinic.owner.PetTypeFormatter.parse(PetTypeFormatter.java:53)",
                "org.thymeleaf.spring6.util.SpringSelectedValueComparator.exhaustiveCompare("
                        + "SpringSelectedValueComparator.java:188)",
                "org.thymeleaf.spring6.processor.SpringOptionFieldTagProcessor.doProcess("
                        + "SpringOptionFieldTagProcessor.java:61)"));
        ApplicationFrames lazyLoad = ApplicationFrames.of(List.of(
                "com.example.Order$HibernateProxy$x1.getLines(Unknown Source)",
                "tools.jackson.databind.ser.BeanSerializer.serialize(BeanSerializer.java:180)"));
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/owners/{ownerId}/pets/new",
                    sql("select pt1_0.id,pt1_0.name from types pt1_0 order by pt1_0.name", formatter, 300));
            request("GET", "/api/orders/{id}", sql("select * from lines where order_id = 1", lazyLoad, 300));
        }

        Map<String, RuntimeObservationDto> lazy =
                byKind(service(InsightsStack.SPRING_MVC).report(), LazySqlAfterHandler.KIND).stream()
                        .collect(Collectors.toMap(RuntimeObservationDto::subject, Function.identity()));

        RuntimeObservationDto view = lazy.get("GET /owners/{ownerId}/pets/new");
        assertThat(view.status()).isEqualTo("OBSERVED");
        assertThat(view.sentence()).endsWith("outside a transaction, while the view was rendered.");
        assertThat(view.whatToCheck().get(0))
                .startsWith("The view ran this query while it was rendered, through `PetTypeFormatter.parse`:")
                .contains("in the handler and add it to the model");
        assertThat(view.whatToCheck()).noneMatch(check -> check.contains("open-in-view") || check.contains("lazy"));
        RuntimeObservationDetailDto detail = service(InsightsStack.SPRING_MVC).insight(view.id());
        assertThat(detail.rows())
                .extracting(row -> row.cells().get(2))
                .as("the application frame the view called is the call site, not the template engine's")
                .containsOnly("org.springframework.samples.petclinic.owner.PetTypeFormatter.parse("
                        + "PetTypeFormatter.java:53)");

        RuntimeObservationDto serialization = lazy.get("GET /api/orders/{id}");
        assertThat(serialization.sentence()).endsWith("while the response was written.");
        assertThat(serialization.whatToCheck())
                .anySatisfy(check -> assertThat(check).contains("lazy association"))
                .anySatisfy(check -> assertThat(check).contains("spring.jpa.open-in-view=false"));
    }

    @Test
    void sqlAPropagatedTaskRanWhileTheResponseWasWrittenIsNotLazyLoading() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/insights/after-response",
                    sql("select * from orders where id = 1", RequestPhase.HANDLER, 150),
                    // Defensively ignored even if a recorder stamped the request's phase on the task's statement.
                    async(sql("update audit set seen = 1", RequestPhase.RESPONSE, 300)));
        }

        RuntimeInsightsReportDto report = service(InsightsStack.SPRING_MVC).report();

        assertThat(byKind(report, LazySqlAfterHandler.KIND)).isEmpty();
        assertThat(check(report, LazySqlAfterHandler.KIND).status()).isNotEqualTo("OBSERVED");
    }

    private RuntimeInsightsService service(InsightsStack stack) {
        return new RuntimeInsightsService(journal, null, null, stack, List::of);
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .toList();
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report, String kind) {
        return report.checks().stream()
                .filter(check -> check.kind().equals(kind))
                .findFirst()
                .orElseThrow();
    }

    private static Child transaction(
            String method, boolean nested, boolean savepoint, boolean rolledBack, long start, long end) {
        return new Child(
                JournalSource.TRANSACTION,
                end - start,
                new TransactionPayload(method, rolledBack, nested, savepoint, start),
                null);
    }

    private static Child sql(String sql, RequestPhase phase, long completedNanos) {
        return new Child(
                JournalSource.SQL,
                1,
                new SqlPayload(sql, "Repo.run:1", "db", false, null, phase, completedNanos),
                null);
    }

    private static Child write(String dataSource, long completedNanos) {
        return new Child(
                JournalSource.SQL,
                0,
                new SqlPayload(
                        "insert into audit values (?)",
                        "Repo.run:1",
                        dataSource,
                        false,
                        null,
                        RequestPhase.HANDLER,
                        completedNanos,
                        0,
                        SqlPayload.Provenance.EXECUTION),
                null);
    }

    private static Child sql(String sql, ApplicationFrames frames, long completedNanos) {
        return new Child(
                JournalSource.SQL,
                1,
                new SqlPayload(sql, frames.callSite(), "db", false, frames, RequestPhase.RESPONSE, completedNanos),
                null);
    }

    private static Child async(Child child) {
        return new Child(child.source(), child.nanos(), child.payload(), ExecutionIds.nextAsync());
    }

    private void request(String method, String template, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            CorrelationContext owner =
                    child.executionId() == null ? context : context.withExecutionId(child.executionId());
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), owner, "http-1", null, false, child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                30_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, template.replace("{id}", "42"), template, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload, String executionId) {}
}
