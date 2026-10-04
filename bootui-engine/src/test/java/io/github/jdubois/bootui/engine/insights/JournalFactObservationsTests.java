package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.FaultTolerancePayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JournalFactObservationsTests {

    private static final String ORDERS = "GET /api/orders/{id}";

    private RuntimeJournal journal = journal(JournalSource.all());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void exceptionHotspotsMarkWhatThePreviousRunNeverRaisedOnARouteItServed() {
        RuntimeJournal previousJournal = journal;
        request(previousJournal, "/api/orders/{id}", 200, exception("old", "java.lang.IllegalStateException"));
        request(previousJournal, "/api/orders/{id}", 200);
        RunSummary previous = summaryOf(previousJournal);
        journal = journal(JournalSource.all());

        request("/api/orders/{id}", 500, exception("lazy", "org.hibernate.LazyInitializationException"));
        request("/api/orders/{id}", 500, exception("lazy", "org.hibernate.LazyInitializationException"));
        request("/api/orders/{id}", 200, exception("old", "java.lang.IllegalStateException"));
        request("/api/customers/{id}", 500, exception("lazy", "org.hibernate.LazyInitializationException"));

        RuntimeInsightsReportDto report =
                service(InsightsStack.SPRING_MVC, previous).report();
        List<RuntimeObservationDto> hotspots = byKind(report, ExceptionHotspots.KIND);

        RuntimeObservationDto lazy = hotspots.stream()
                .filter(o -> o.subject().equals(ORDERS) && o.sentence().contains("LazyInitializationException"))
                .findFirst()
                .orElseThrow();
        assertThat(lazy.sentence())
                .isEqualTo("`" + ORDERS + "` recorded `LazyInitializationException` in 2 of 3 requests (2"
                        + " occurrences), not observed in the previous run, which served this route 2 times.");
        assertThat(lazy.whatToCheck().get(0)).contains("join fetch");
        assertThat(lazy.affected()).isEqualTo(2);
        RuntimeObservationDto old = hotspots.stream()
                .filter(o -> o.sentence().contains("IllegalStateException"))
                .findFirst()
                .orElseThrow();
        assertThat(old.sentence()).doesNotContain("previous run");
        assertThat(old.whatToCheck()).containsExactly(ExceptionHotspots.GENERIC_CHECK);
        RuntimeObservationDto customers = hotspots.stream()
                .filter(o -> o.subject().equals("GET /api/customers/{id}"))
                .findFirst()
                .orElseThrow();
        assertThat(customers.sentence())
                .as("the previous run never served this route, so nothing can be new on it")
                .doesNotContain("previous run");
        assertThat(service(InsightsStack.SPRING_MVC, previous).report().observations())
                .extracting(RuntimeObservationDto::id)
                .as("ids are stable across reads")
                .containsAll(report.observations().stream()
                        .map(RuntimeObservationDto::id)
                        .toList());
    }

    @Test
    void wellKnownFrameworkExceptionsCarryTheirSpecificCheckBeforeTheGenericOne() {
        request(
                "/api/orders/{id}",
                500,
                exception("rb", "org.springframework.transaction.UnexpectedRollbackException"));

        RuntimeObservationDto hotspot =
                byKind(service(null, null).report(), ExceptionHotspots.KIND).get(0);

        assertThat(hotspot.whatToCheck()).hasSize(2);
        assertThat(hotspot.whatToCheck().get(0)).contains("rollback-only").contains("REQUIRES_NEW");
        assertThat(hotspot.whatToCheck().get(1)).isEqualTo(ExceptionHotspots.GENERIC_CHECK);
    }

    @Test
    void exceptionChecksPreferCausesAndIncludeSubtypeAdviceAcrossOccurrencesOfOneGroup() {
        String wrapper = "org.springframework.http.converter.HttpMessageNotWritableException";
        request(
                "/api/orders/{id}",
                500,
                child(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "wrapped",
                                wrapper,
                                "wrapped",
                                List.of("org.hibernate.LazyInitializationException", wrapper))));
        request(
                "/api/orders/{id}",
                500,
                child(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "wrapped",
                                wrapper,
                                "wrapped",
                                List.of(
                                        "com.example.DuplicateKeyException",
                                        "org.springframework.dao.DataIntegrityViolationException",
                                        wrapper))));
        request(
                "/api/pool",
                500,
                child(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "pool",
                                "java.lang.IllegalStateException",
                                "pool",
                                List.of(
                                        "com.example.DriverConnectionException",
                                        "java.sql.SQLTransientConnectionException"))));
        request(
                "/api/orders/{id}",
                500,
                child(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "wrapped",
                                wrapper,
                                "wrapped",
                                List.of("jakarta.persistence.OptimisticLockException", wrapper))));

        List<RuntimeObservationDto> found = byKind(service(null, null).report(), ExceptionHotspots.KIND);
        assertThat(found.get(0).whatToCheck()).hasSize(3);
        assertThat(found.get(0).whatToCheck().get(0)).contains("join fetch");
        assertThat(found.get(0).whatToCheck().get(1)).contains("constraint");
        assertThat(found.get(0).whatToCheck()).last().isEqualTo(ExceptionHotspots.GENERIC_CHECK);
        assertThat(found.get(0).whatToCheck()).noneMatch(check -> check.contains("bidirectional"));
        assertThat(found.get(0).limitations()).anyMatch(text -> text.contains("first two"));
        assertThat(found.get(1).whatToCheck().get(0)).contains("pool timed out", "connectivity");
    }

    @Test
    void parsingExceptionSubclassesDoNotGetSerializationRecursionAdvice() {
        request(
                "/api/orders/{id}",
                400,
                child(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload(
                                "input",
                                "org.springframework.http.converter.HttpMessageNotReadableException",
                                "input",
                                List.of(
                                        "com.fasterxml.jackson.databind.exc.MismatchedInputException",
                                        "com.fasterxml.jackson.databind.JsonMappingException"))));
        assertThat(byKind(service(null, null).report(), ExceptionHotspots.KIND))
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.whatToCheck()).containsExactly(ExceptionHotspots.GENERIC_CHECK));
    }

    @Test
    void errorsBehind2xxCountsSeveralRequestsInPlainWords() {
        for (int i = 0; i < 2; i++) {
            request(
                    "/api/orders/{id}",
                    200,
                    exception("e" + i, "java.lang.IllegalStateException"),
                    child(
                            JournalSource.TRANSACTION,
                            new TransactionPayload("OrderService.place", true, false, false, 1)));
        }

        assertThat(byKind(service(null, null).report(), ErrorsBehind2xx.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence())
                        .isEqualTo("`" + ORDERS + "` answered 2xx in 2 of 2 successful requests: 2 requests whose"
                                + " transaction rolled back, 2 requests that recorded an exception."));
    }

    @Test
    void errorsBehind2xxOrderEvidenceByStrengthAndReportRecoveredRequestsApart() {
        request(
                "/api/orders/{id}",
                200,
                exception("e", "java.lang.IllegalStateException"),
                child(JournalSource.TRANSACTION, new TransactionPayload("OrderService.place", true, false, false, 1)));
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                child(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(
                                "pricing",
                                "RETRY",
                                "Pricing.lookup",
                                "RETRY",
                                1,
                                null,
                                "IllegalArgumentException",
                                true,
                                true)),
                child(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(
                                "pricing", "RETRY", "Pricing.lookup", "SUCCESS", 2, null, null, false, true)));
        // Counterexamples: a 4xx the client library threw, a nested savepoint rolling back alone, and a 500.
        request(
                "/api/orders/{id}",
                200,
                child(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload("GET", "stock:8080", "/items/1", 404, "RestClient", true)),
                child(JournalSource.TRANSACTION, new TransactionPayload("StockService.reserve", true, true, true, 2)));
        request("/api/orders/{id}", 500, exception("e", "java.lang.IllegalStateException"));
        request(
                "/api/orders/{id}",
                201,
                child(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload("POST", "stock:8080", "/reserve", 503, "RestClient", true)));

        Map<String, RuntimeObservationDto> found = byKind(service(null, null).report(), ErrorsBehind2xx.KIND).stream()
                .collect(Collectors.toMap(RuntimeObservationDto::id, Function.identity()));

        RuntimeObservationDto unrecovered =
                found.get(RuntimeInsightsService.idOf(ErrorsBehind2xx.KIND, ORDERS + ":unrecovered"));
        assertThat(unrecovered.sentence())
                .isEqualTo("`" + ORDERS + "` answered 2xx in 2 of 4 successful requests: 1 request whose transaction"
                        + " rolled back, 1 request that recorded an exception, 1 request that received a 5xx or failed"
                        + " downstream call.");
        assertThat(unrecovered.exemplarRequestIds()).containsExactly("r1", "r5");
        assertThat(unrecovered.whatToCheck()).hasSize(2);
        RuntimeObservationDto recovered =
                found.get(RuntimeInsightsService.idOf(ErrorsBehind2xx.KIND, ORDERS + ":recovered"));
        assertThat(recovered.affected()).isEqualTo(1);
        assertThat(recovered.sentence())
                .endsWith("in 1 of 4 successful requests: 1 request that recorded an exception; a retry or fallback"
                        + " recovered.");
        assertThat(found).hasSize(2);
    }

    @Test
    void aRecoveredRetryDoesNotHideUnrelatedErrorsOrConsumeEveryExceptionOfItsClass() {
        request(
                "/api/orders/{id}",
                200,
                exception("unrelated", "java.lang.IllegalArgumentException"),
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "RETRY", "IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "SUCCESS", null),
                exception("late", "java.lang.IllegalStateException"),
                child(JournalSource.LOG, new LogPayload("com.example.Stock", "ERROR", "Stock failed", null)),
                child(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload("GET", "stock:8080", "/items/1", 503, "RestClient", true)),
                child(JournalSource.TRANSACTION, new TransactionPayload("OrderService.place", true, false, false, 1)));

        RuntimeInsightsService insights = service(null, null);
        List<RuntimeObservationDto> found = byKind(insights.report(), ErrorsBehind2xx.KIND);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).sentence()).contains("transaction rolled back", "ERROR log", "downstream");
        assertThat(insights.insight(found.get(0).id()).rows().get(0).cells().get(2))
                .contains("IllegalArgumentException", "IllegalStateException");
        assertThat(found.get(1).sentence()).contains("a retry or fallback recovered");
        assertThat(insights.insight(found.get(1).id()).rows().get(0).cells().get(2))
                .isEqualTo("exception: IllegalArgumentException");
        assertThat(found).allSatisfy(observation -> {
            assertThat(observation.affected()).isEqualTo(1);
            assertThat(observation.exemplarRequestIds()).containsExactly("r1");
        });
    }

    @Test
    void recoveryRequiresTheSamePolicyTypeTargetAndTerminalOutcome() {
        for (String outcome : List.of("SUCCESS", "RETRY_EXHAUSTED")) {
            request(
                    "/api/orders/{id}",
                    200,
                    exception("pricing", "java.lang.IllegalArgumentException"),
                    policy("pricing", "Pricing.lookup", "RETRY", "IllegalArgumentException"),
                    policy(outcome.equals("SUCCESS") ? "stock" : "pricing", "Pricing.lookup", outcome, null));
        }
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "RETRY", "IllegalArgumentException"),
                policy("pricing", "Stock.lookup", "SUCCESS", null));
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "RETRY", "IllegalArgumentException"));
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "FALLBACK", "IllegalStateException"));
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "RETRY", "IllegalArgumentException"),
                child(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(
                                "pricing",
                                "CIRCUIT_BREAKER",
                                "Pricing.lookup",
                                "SUCCESS",
                                1,
                                null,
                                null,
                                false,
                                false)));

        assertThat(byKind(service(null, null).report(), ErrorsBehind2xx.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.affected()).isEqualTo(6);
                    assertThat(observation.sentence()).doesNotContain("recovered");
                });
    }

    @Test
    void successfulFallbackRecoversOnlyItsEarlierMatchingException() {
        request(
                "/api/orders/{id}",
                200,
                exception("pricing", "java.lang.IllegalArgumentException"),
                policy("pricing", "Pricing.lookup", "FALLBACK", "IllegalArgumentException"),
                exception("fallback", "java.lang.IllegalArgumentException"));

        assertThat(byKind(service(null, null).report(), ErrorsBehind2xx.KIND))
                .hasSize(2)
                .allSatisfy(observation -> assertThat(observation.affected()).isEqualTo(1));
    }

    @Test
    void nestedExceptionClassNamesMatchTheProvidersSimpleFailureCategory() {
        request(
                "/api/orders/{id}",
                200,
                exception("server", "org.springframework.web.client.HttpServerErrorException$InternalServerError"),
                policy("pricing", "Pricing.lookup", "RETRY", "InternalServerError"),
                policy("pricing", "Pricing.lookup", "SUCCESS", null));
        assertThat(byKind(service(null, null).report(), ErrorsBehind2xx.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence()).contains("recovered"));
    }

    private static Child policy(String name, String target, String outcome, String category) {
        return child(
                JournalSource.FAULT_TOLERANCE,
                new FaultTolerancePayload(
                        name,
                        "RETRY",
                        target,
                        outcome,
                        1,
                        null,
                        category,
                        "RETRY".equals(outcome) || "RETRY_EXHAUSTED".equals(outcome),
                        true));
    }

    @Test
    void errorsBehind2xxSayWhichEvidenceTheJournalDoesNotRecord() {
        journal.close();
        journal = journal(EnumSet.of(JournalSource.HTTP, JournalSource.EXCEPTION));
        request("/api/orders/{id}", 200, exception("e", "java.lang.IllegalStateException"));

        RuntimeInsightsReportDto report = service(null, null).report();

        RuntimeInsightCheckDto check = checks(report).get(ErrorsBehind2xx.KIND);
        assertThat(check.status()).isEqualTo("EVALUATED");
        assertThat(check.reason()).contains("Without the transaction source").contains("Without the log source");
        assertThat(byKind(report, ErrorsBehind2xx.KIND).get(0).limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("Without the rest-client source"));

        journal.close();
        journal = journal(EnumSet.of(JournalSource.HTTP, JournalSource.SQL));
        assertThat(checks(service(null, null).report())
                        .get(ErrorsBehind2xx.KIND)
                        .status())
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void eventLoopBlockingCountsStatementsStartedOnAnEventLoopAndDoesNotApplyToSpringMvc() {
        for (int i = 0; i < 3; i++) {
            request(
                    "/api/orders/{id}",
                    200,
                    sql(ThreadKind.EVENT_LOOP, "select * from orders where id = 1"),
                    sql(ThreadKind.REACTOR_SCHEDULER, "select * from lines where order_id = 1"));
        }
        request("/api/customers/{id}", 200, sql(ThreadKind.REACTOR_SCHEDULER, "select 1"));

        List<RuntimeObservationDto> blocking =
                byKind(service(InsightsStack.SPRING_WEBFLUX, null).report(), EventLoopBlocking.KIND);

        assertThat(blocking).singleElement().satisfies(observation -> {
            assertThat(observation.sentence())
                    .isEqualTo("`" + ORDERS + "` started 3 JDBC statements on an event-loop thread, in 3 of 3"
                            + " requests, at `OrderRepository.find:12`; it recurs.");
            assertThat(observation.whatToCheck().get(0)).contains("boundedElastic");
        });
        assertThat(byKind(service(InsightsStack.QUARKUS, null).report(), EventLoopBlocking.KIND)
                        .get(0)
                        .whatToCheck()
                        .get(0))
                .contains("@Blocking");
        RuntimeInsightCheckDto mvc =
                checks(service(InsightsStack.SPRING_MVC, null).report()).get(EventLoopBlocking.KIND);
        assertThat(mvc.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(mvc.reason()).contains("Spring MVC");
    }

    @Test
    void frameworkWarningsAreGroupedPerRouteWithAKnownCodesCheckAndApplicationLoggersAreLeftOut() {
        String template = "HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory";
        request(
                "/api/orders/{id}",
                200,
                child(JournalSource.LOG, new LogPayload("org.hibernate.orm.query", "WARN", template, null)),
                child(JournalSource.LOG, new LogPayload("org.hibernate.orm.query", "WARN", template, null)));
        request("/api/orders/{id}", 200);
        request(
                "/api/orders/{id}",
                200,
                child(JournalSource.LOG, new LogPayload("com.example.Orders", "WARN", "Slow order {}", null)));

        List<RuntimeObservationDto> warnings = byKind(service(null, null).report(), FrameworkWarningsByRoute.KIND);

        assertThat(warnings).singleElement().satisfies(observation -> {
            assertThat(observation.sentence())
                    .startsWith("`" + ORDERS + "` logged `WARN` from `query` in 1 of 3 requests (2 events): ");
            assertThat(observation.whatToCheck())
                    .first()
                    .satisfies(check -> assertThat(check).contains("paginated in memory"));
            assertThat(observation.whatToCheck()).hasSize(2);
        });
    }

    private RuntimeInsightsService service(InsightsStack stack, RunSummary previous) {
        return new RuntimeInsightsService(
                journal, null, null, stack, previous == null ? List::of : () -> List.of(previous));
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .toList();
    }

    private static Map<String, RuntimeInsightCheckDto> checks(RuntimeInsightsReportDto report) {
        return report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
    }

    private static RunSummary summaryOf(RuntimeJournal journal) {
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(journal.entries());
        RunSummary summary = RunSummary.of(journal.run(), aggregates.snapshot(), System.currentTimeMillis());
        journal.close();
        return summary;
    }

    private static Child exception(String signature, String className) {
        return child(JournalSource.EXCEPTION, new ExceptionPayload("g-" + signature, className, signature));
    }

    private static Child sql(ThreadKind kind, String sql) {
        return new Child(
                JournalSource.SQL, 2_000_000, kind, new SqlPayload(sql, "OrderRepository.find:12", "db", false));
    }

    private static Child child(JournalSource source, RuntimeEventPayload payload) {
        return new Child(source, 1_000_000, ThreadKind.WORKER, payload);
    }

    private void request(String template, int status, Child... children) {
        request(journal, template, status, children);
    }

    private void request(RuntimeJournal target, String template, int status, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            target.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "loop-1", child.kind(), false, child.payload()));
        }
        target.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                30_000_000,
                context,
                "loop-1",
                null,
                false,
                new HttpPayload("GET", template.replace("{id}", "42"), template, null, status)));
        try {
            assertThat(target.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static RuntimeJournal journal(Set<JournalSource> sources) {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, sources), RunIdentity.start());
    }

    private record Child(JournalSource source, long nanos, ThreadKind kind, RuntimeEventPayload payload) {}
}
