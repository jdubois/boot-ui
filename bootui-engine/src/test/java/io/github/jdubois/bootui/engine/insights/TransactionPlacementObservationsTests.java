package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    void writesCommittedByTwoIndependentUnitsAreReportedAndJoinedOrRolledBackOnesAreNot() {
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
                    .isEqualTo("`POST /api/orders` committed its writes in 2 independent units in 2 of 4 requests"
                            + " that wrote: if these writes must succeed together, one may persist while another"
                            + " fails.");
            assertThat(observation.exemplarRequestIds()).containsExactly("r1", "r2");
        });
        assertThat(service(InsightsStack.SPRING_MVC)
                        .insight(SplitTransactionWrites.KIND + ":POST /api/orders")
                        .rows()
                        .get(1)
                        .cells()
                        .get(2))
                .isEqualTo("OrderService.place: 2 writes, first `insert into orders values (?)`; AuditService.log"
                        + " (nested): 1 write, first `insert into audit values (?)`");
        assertThat(check(service(InsightsStack.QUARKUS).report(), SplitTransactionWrites.KIND)
                        .status())
                .isEqualTo("NOT_APPLICABLE");
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
                new TransactionPayload(method, rolledBack, nested, savepoint, start));
    }

    private static Child sql(String sql, RequestPhase phase, long completedNanos) {
        return new Child(
                JournalSource.SQL, 1, new SqlPayload(sql, "Repo.run:1", "db", false, null, phase, completedNanos));
    }

    private void request(String method, String template, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-1", null, false, child.payload()));
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

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
