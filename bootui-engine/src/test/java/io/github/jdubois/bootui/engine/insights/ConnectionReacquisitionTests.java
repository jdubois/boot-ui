package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code connection-reacquisition}: a Hibernate session that borrowed a JDBC connection two times or more in three
 * requests of a route, with the call sites that ran after the first transaction only when it can place them.
 */
class ConnectionReacquisitionTests {

    private static final long MS = 1_000_000;
    private static final String ROUTE = "GET /orders";

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aSessionThatBorrowedTwoToFiveConnectionsInEachOfFourRequestsIsReportedWithItsEvidence() {
        for (int acquisitions = 2; acquisitions <= 5; acquisitions++) {
            request(orm(acquisitions, 3 * MS, acquisitions + 1));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo(ROUTE);
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .isEqualTo("`GET /orders` borrowed a JDBC connection up to 5 times in one Hibernate session,"
                            + " in 4 of 4 requests.");
            assertThat(observation.eligible()).isEqualTo(4);
            assertThat(observation.affected()).isEqualTo(4);
            assertThat(observation.exemplarRequestIds()).hasSize(3).first().isEqualTo("r4");
            assertThat(observation.whatToCheck()).hasSize(2);
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("not the pool's borrow count"))
                    .anyMatch(limitation -> limitation.contains("JTA")
                            && limitation.contains("DELAYED_ACQUISITION_AND_RELEASE_AFTER_STATEMENT"))
                    .anyMatch(limitation -> limitation.contains("`lazy-sql-after-handler`"));
            assertThat(observation.listed())
                    .as("a kind added after the validation is reached on request")
                    .isFalse();
            assertThat(observation.unlistedReason()).isNotBlank();
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.columns())
                    .containsExactly("Request", "Acquisitions", "Acquisition time (ms)", "Session SQL");
            assertThat(detail.rows())
                    .extracting(RuntimeObservationRowDto::cells)
                    .containsExactly(
                            List.of("r4", "5", "3.0", "6"),
                            List.of("r3", "4", "3.0", "5"),
                            List.of("r2", "3", "3.0", "4"),
                            List.of("r1", "2", "3.0", "3"));
        });
    }

    @Test
    void aSessionThatBorrowedOneConnectionIsNeverReported() {
        for (int i = 0; i < 5; i++) {
            request(orm(1, MS, 8));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).isEmpty();
    }

    @Test
    void onlyTwoQualifyingRequestsAreInsufficient() {
        request(orm(3, MS, 4));
        request(orm(2, MS, 3));
        request(orm(1, MS, 2));

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("INSUFFICIENT");
            assertThat(observation.affected()).isEqualTo(2);
            assertThat(observation.sentence())
                    .isEqualTo("`GET /orders` borrowed a JDBC connection up to 3 times in one Hibernate session,"
                            + " in 2 of 3 requests. 3 requests with 2 acquisitions or more are needed to call it a"
                            + " pattern.");
        });
    }

    @Test
    void requestsWithoutAHibernateSessionAreNotCountedAsEligible() {
        for (int i = 0; i < 3; i++) {
            request(orm(2, MS, 3));
        }
        request(null);
        request(null);

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence()).endsWith("in 3 of 3 requests.");
        });
    }

    @Test
    void aRouteWhoseRequestsHaveNoHibernateSessionIsNotReported() {
        for (int i = 0; i < 4; i++) {
            request(null);
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).isEmpty();
    }

    @Test
    void theStatementsAfterTheFirstTransactionEndedAreNamedWhenTheyCanBePlaced() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(3, 2 * MS, 4),
                    sqlAt("select * from orders", "OrderRepository.findAll:12", 150),
                    transactionAt("OrderService.list", 100, 200),
                    sqlAt("select * from lines where order_id = ?", "OrderView.render:40", 250),
                    sqlAt("select * from lines where order_id = ?", "OrderView.render:40", 300),
                    sqlAt("select * from customer where id = ?", "CustomerView.render:51", 350));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.columns())
                    .containsExactly(
                            "Request",
                            "Acquisitions",
                            "Acquisition time (ms)",
                            "Session SQL",
                            "Call sites after the first transaction");
            assertThat(detail.rows())
                    .allSatisfy(row -> assertThat(row.cells().get(4))
                            .as("the statement inside the transaction is left out, and a repeated site counts once")
                            .isEqualTo("OrderView.render:40, CustomerView.render:51"));
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("after its first transaction ended"))
                    .noneMatch(limitation -> limitation.contains("Call sites are omitted"));
        });
    }

    @Test
    void aTransactionEndingTheRequestLeavesNoCallSitesButKeepsTheFinding() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(2, MS, 2),
                    sqlAt("select 1", "A.run:1", 120),
                    sqlAt("select 2", "B.run:2", 150),
                    transactionAt("OrderService.list", 100, 200));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(detail.columns()).hasSize(4);
            assertThat(observation.limitations()).noneMatch(limitation -> limitation.contains("Call sites"));
        });
    }

    @Test
    void withoutRecordedTransactionsTheCallSitesAreOmittedAndTheLimitationSaysSo() {
        for (int i = 0; i < 3; i++) {
            request(orm(3, MS, 3), sqlAt("select 1", "A.run:1", 120), sqlAt("select 2", "B.run:2", 450));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(detail.columns()).doesNotContain("Call sites after the first transaction");
            assertThat(observation.limitations())
                    .anyMatch(limitation ->
                            limitation.contains("Call sites are omitted") && limitation.contains("none was recorded"));
        });
    }

    @Test
    void aTransactionWithoutAMonotonicTimeLeavesNoCallSitesAndIsCountedAsUnplaced() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(2, MS, 2),
                    sqlAt("select 1", "A.run:1", 120),
                    transactionAt("OrderService.list", -1, 200),
                    sqlAt("select 2", "B.run:2", 450));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.columns()).hasSize(4);
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("Call sites are omitted for 3 requests"));
        });
    }

    @Test
    void aSequentialWorkerHandoffAfterTheFirstTransactionIsNamedToo() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(2, MS, 2),
                    transactionAt("OrderService.list", 100, 200),
                    sqlAt("select 1", "Same.run:1", 300),
                    new Child(
                            JournalSource.SQL,
                            "worker-9",
                            new SqlPayload("select 2", "Other.run:2", "db", false, null, RequestPhase.HANDLER, 320)));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.rows().get(0).cells().get(4)).isEqualTo("Same.run:1, Other.run:2");
        });
    }

    @Test
    void anExecutedStatementWithoutAMonotonicTimeLeavesTheRequestUnplacedAndSaysSo() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(2, MS, 2),
                    transactionAt("OrderService.list", 100, 200),
                    sqlAt("select 1", "Timed.run:1", 300),
                    sqlAt("select 2", "Untimed.run:2", -1));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(detail.columns()).hasSize(4);
            assertThat(observation.limitations())
                    .anyMatch(limitation -> limitation.contains("Call sites are omitted for 3 requests"));
        });
    }

    @Test
    void anUnplacedRequestIsNotShownAsOneWhoseStatementsAllRanInsideTheTransaction() {
        request(orm(2, MS, 2), transactionAt("OrderService.list", 100, 200), sqlAt("select 1", "Late.run:1", 300));
        request(orm(2, MS, 2), sqlAt("select 1", "Early.run:1", 120));
        request(orm(2, MS, 2), transactionAt("OrderService.list", 100, 200), sqlAt("select 1", "In.run:1", 150));

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.rows())
                    .extracting(row -> row.cells().get(4))
                    .containsExactlyInAnyOrder("Late.run:1", "not placed", "-");
        });
    }

    @Test
    void theFirstTransactionIsTheOuterOneOfTheSessionsThread() {
        for (int i = 0; i < 3; i++) {
            request(
                    orm(2, MS, 2),
                    // Same start: the inner one ends first but the outer one is the first transaction.
                    transactionAt("Audit.log", 100, 150),
                    transactionAt("OrderService.list", 100, 400),
                    sqlAt("select 1", "Inside.run:1", 300),
                    sqlAt("select 2", "After.run:2", 500),
                    // Another thread's earlier transaction does not stand for the session's.
                    new Child(
                            JournalSource.TRANSACTION,
                            "worker-9",
                            new TransactionPayload("Other.tx", false, false, false, 50),
                            10));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.rows())
                    .allSatisfy(row -> assertThat(row.cells().get(4)).isEqualTo("After.run:2"));
        });
    }

    @Test
    void aDisabledTransactionsPanelKeepsTheCountAndOmitsTheCallSites() {
        for (int i = 0; i < 3; i++) {
            request(orm(2, MS, 2), transactionAt("OrderService.list", 100, 200), sqlAt("select 1", "Late.run:1", 300));
        }

        RuntimeInsightsService withoutTransactions = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals(BootUiPanels.TRANSACTIONS), InsightsStack.SPRING_MVC, List::of);
        assertThat(withoutTransactions.report().observations())
                .filteredOn(observation -> observation.kind().equals(ConnectionReacquisition.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.status()).isEqualTo("OBSERVED");
                    assertThat(observation.limitations())
                            .anyMatch(limitation -> limitation.contains("Call sites are omitted"));
                    assertThat(withoutTransactions.insight(observation.id()).columns())
                            .doesNotContain("Call sites after the first transaction");
                });
    }

    @Test
    void aPropagatedTasksStatementsAreNotCallSitesOfTheRequest() {
        for (int i = 0; i < 3; i++) {
            String executionId = ExecutionIds.nextAsync();
            request(
                    orm(2, MS, 2),
                    transactionAt("OrderService.list", 100, 200),
                    sqlAt("select 1", "Late.run:1", 300),
                    new Child(
                            JournalSource.SQL,
                            "http-1",
                            new SqlPayload("select 2", "Task.run:2", "db", false, null, RequestPhase.HANDLER, 320),
                            MS,
                            executionId));
        }

        assertThat(observations(InsightsStack.SPRING_MVC)).singleElement().satisfies(observation -> {
            RuntimeObservationDetailDto detail =
                    service(InsightsStack.SPRING_MVC).insight(observation.id());
            assertThat(detail.rows())
                    .allSatisfy(row -> assertThat(row.cells().get(4)).isEqualTo("Late.run:1"));
        });
    }

    @Test
    void quarkusAndWebFluxReportItWithoutPointingAtSqlAfterTheHandler() {
        for (int i = 0; i < 3; i++) {
            request(orm(2, MS, 2));
        }

        for (InsightsStack stack : List.of(InsightsStack.QUARKUS, InsightsStack.SPRING_WEBFLUX)) {
            assertThat(observations(stack)).singleElement().satisfies(observation -> {
                assertThat(observation.status()).isEqualTo("OBSERVED");
                assertThat(observation.limitations()).noneMatch(limitation -> limitation.contains("lazy-sql-after"));
            });
        }
        for (InsightsStack stack : List.of(InsightsStack.QUARKUS)) {
            assertThat(observations(stack)).singleElement().satisfies(observation -> {
                assertThat(observation.limitations())
                        .anyMatch(limitation -> limitation.contains("Call sites are omitted"));
            });
        }
    }

    private RuntimeInsightsService service(InsightsStack stack) {
        return new RuntimeInsightsService(journal, null, null, stack, List::of);
    }

    private List<RuntimeObservationDto> observations(InsightsStack stack) {
        return service(stack).report().observations().stream()
                .filter(observation -> observation.kind().equals(ConnectionReacquisition.KIND))
                .toList();
    }

    private record Child(
            JournalSource source, String thread, RuntimeEventPayload payload, long nanos, String executionId) {

        Child(JournalSource source, String thread, RuntimeEventPayload payload) {
            this(source, thread, payload, MS, null);
        }

        Child(JournalSource source, String thread, RuntimeEventPayload payload, long nanos) {
            this(source, thread, payload, nanos, null);
        }
    }

    private static Child sqlAt(String sql, String callSite, long completedNanos) {
        return new Child(
                JournalSource.SQL,
                "http-1",
                new SqlPayload(sql, callSite, "db", false, null, RequestPhase.HANDLER, completedNanos));
    }

    private static Child transactionAt(String method, long startNanos, long endNanos) {
        return new Child(
                JournalSource.TRANSACTION,
                "http-1",
                new TransactionPayload(method, false, false, false, startNanos),
                endNanos - Math.max(0, startNanos));
    }

    private static OrmPayload orm(int acquisitions, long acquisitionNanos, int statements) {
        return new OrmPayload(
                null, statements, statements * MS, acquisitions, acquisitionNanos, 0, 0, 0, 0, 0, -1, 0, 0, 0);
    }

    /** One request of {@link #ROUTE}; {@code orm} is {@code null} when no Hibernate session was recorded. */
    private void request(OrmPayload orm, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            CorrelationContext owner =
                    child.executionId() == null ? context : context.withExecutionId(child.executionId());
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), owner, child.thread(), null, false, child.payload()));
        }
        if (orm != null) {
            journal.offer(RuntimeEvent.of(JournalSource.ORM, 1_000, 10 * MS, context, "http-1", null, false, orm));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                30 * MS,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/orders", "/orders", null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
