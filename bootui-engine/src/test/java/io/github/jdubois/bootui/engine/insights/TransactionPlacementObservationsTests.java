package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
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
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
                        .insight(RuntimeInsightsService.idOf(SplitTransactionWrites.KIND, "POST /api/orders"))
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
