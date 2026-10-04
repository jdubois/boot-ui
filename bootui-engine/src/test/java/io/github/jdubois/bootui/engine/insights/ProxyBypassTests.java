package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.CachePayload;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxyBypassTests {

    private static final String REPOSITORY = "com.example.OrderRepository.save(OrderRepository.java:10)";
    private static final String PLACE = "com.example.OrderService.place(OrderService.java:20)";
    private static final String CHECKOUT = "com.example.OrderService.checkout(OrderService.java:12)";
    private static final String PRICE = "com.example.PriceService.price(PriceService.java:30)";
    private static final String NOTIFY = "com.example.Notifier.notifyShipped(Notifier.java:40)";

    private static final ProxyBoundaries BOUNDARIES = frame -> Map.of(
                    PLACE, new ProxyBoundaries.Boundary(true, Set.of(), false),
                    PRICE, new ProxyBoundaries.Boundary(false, Set.of("prices"), false),
                    NOTIFY, new ProxyBoundaries.Boundary(false, Set.of(), true))
            .getOrDefault(frame, ProxyBoundaries.Boundary.NONE);

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aTransactionalMethodWhoseStatementRanOutsideEveryTransactionWasBypassed() {
        // Self-invocation: checkout calls place on this, so no transaction opens.
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", REPOSITORY, PLACE, CHECKOUT)));
        // Through the bean: the proxy opened a transaction around the statement.
        request(
                "POST",
                "/api/orders",
                "http-1",
                List.of(
                        transaction(500, 2_000, "http-1"),
                        sql(
                                1_000,
                                "http-1",
                                REPOSITORY,
                                PLACE,
                                "com.example.OrderController.create(OrderController.java:9)")));

        RuntimeObservationDto finding = single(report());

        assertThat(finding.sentence())
                .isEqualTo("`POST /api/orders` ran `OrderService.place` without its `@Transactional` proxy in 1 of 2"
                        + " requests that ran an annotated method, called from `" + CHECKOUT + "`.");
        assertThat(finding.affected()).isEqualTo(1);
        assertThat(finding.whatToCheck().get(0)).startsWith("Call the method through the bean");
        assertThat(finding.whatToCheck().get(1)).contains("ARCH-SPRING-004");
    }

    @Test
    void aCacheableMethodWithoutAnAccessToItsCacheAndAnAsyncMethodOnTheRequestThreadWereBypassed() {
        request("GET", "/api/prices", "http-1", List.of(sql(1_000, "http-1", PRICE)));
        request("GET", "/api/prices", "http-1", List.of(cache("prices"), sql(1_000, "http-1", PRICE)));
        request("POST", "/api/ship", "http-2", List.of(sql(1_000, "http-2", NOTIFY)));
        request("POST", "/api/ship", "http-2", List.of(sql(1_000, "task-1", NOTIFY)));

        List<RuntimeObservationDto> findings = byKind(report());

        assertThat(findings)
                .extracting(RuntimeObservationDto::sentence)
                .containsExactlyInAnyOrder(
                        "`GET /api/prices` ran `PriceService.price` without its `@Cacheable(prices)` proxy in 1 of 2"
                                + " requests that ran an annotated method.",
                        "`POST /api/ship` ran `Notifier.notifyShipped` without its `@Async` proxy in 1 of 2 requests"
                                + " that ran an annotated method.");
    }

    @Test
    void aRequestThatLostItsCacheAccessToAClearIsLeftOutRatherThanReportedAsBypassed() throws InterruptedException {
        CorrelationContext context = CorrelationContext.forRequest("r1");
        journal.offer(RuntimeEvent.of(
                JournalSource.CACHE, 5_001, 0, context, "http-1", null, false, new CachePayload("prices", "MISS")));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        // Clear recording while the request is still running: its cache access is gone, its statement is not.
        journal.clear();
        Child statement = sql(1_000, "http-1", PRICE);
        journal.offer(RuntimeEvent.of(
                statement.source(), 5_002, statement.nanos(), context, "http-1", null, false, statement.payload()));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                5_000,
                5_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/api/prices", "/api/prices", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        RuntimeInsightsReportDto report = report();

        assertThat(byKind(report)).isEmpty();
        assertThat(report.window().requests()).isZero();
        assertThat(report.limitations())
                .contains("1 request or execution started before events the journal evicted or cleared, so it is left"
                        + " out: some of its events may be missing.");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason())
                        .as("an empty result is not read as nothing found")
                        .contains(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS));
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(HeapGrowthAfterGc.KIND))
                .singleElement()
                .satisfies(check -> assertThat(String.valueOf(check.reason()))
                        .as("it examines collections, not requests")
                        .doesNotContain(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS));
    }

    @Test
    void itDoesNotApplyOnQuarkusWithoutAResolverOrWhenTheResolverSaysWhy() {
        request("POST", "/api/orders", "http-1", List.of(sql(1_000, "http-1", REPOSITORY, PLACE, CHECKOUT)));

        assertThat(reason(InsightsStack.QUARKUS, BOUNDARIES)).contains("ArC intercepts");
        assertThat(reason(InsightsStack.SPRING_MVC, null)).contains("No resolver");
        assertThat(reason(InsightsStack.SPRING_MVC, new ProxyBoundaries() {
                    @Override
                    public Boundary resolve(String frame) {
                        return Boundary.NONE;
                    }

                    @Override
                    public String notApplicable() {
                        return "AspectJ weaving.";
                    }
                }))
                .isEqualTo("AspectJ weaving.");
        assertThat(ProxyBypass.method(PLACE)).isEqualTo("OrderService.place");
    }

    private String reason(InsightsStack stack, ProxyBoundaries boundaries) {
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, stack, null);
        service.setProxyBoundaries(boundaries);
        return service.report().checks().stream()
                .filter(check -> check.kind().equals(ProxyBypass.KIND))
                .findFirst()
                .orElseThrow()
                .reason();
    }

    private RuntimeInsightsReportDto report() {
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setProxyBoundaries(BOUNDARIES);
        return service.report();
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(ProxyBypass.KIND))
                .toList();
    }

    private static RuntimeObservationDto single(RuntimeInsightsReportDto report) {
        List<RuntimeObservationDto> findings = byKind(report);
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    private static Child sql(long completedNanos, String thread, String... frames) {
        return new Child(
                JournalSource.SQL,
                thread,
                100,
                new SqlPayload(
                        "insert into orders values (?)",
                        frames[0],
                        "db",
                        false,
                        ApplicationFrames.of(List.of(frames)),
                        null,
                        completedNanos));
    }

    private static Child transaction(long startNanos, long durationNanos, String thread) {
        return new Child(
                JournalSource.TRANSACTION,
                thread,
                durationNanos,
                new TransactionPayload("OrderService.place", false, false, false, startNanos));
    }

    private static Child cache(String name) {
        return new Child(JournalSource.CACHE, "http-1", 0, new CachePayload(name, "MISS", null));
    }

    private void request(String method, String template, String thread, List<Child> children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        List<RuntimeEvent> events = new ArrayList<>();
        for (Child child : children) {
            events.add(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, child.thread(), null, false, child.payload()));
        }
        events.add(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                5_000_000,
                context,
                thread,
                null,
                false,
                new HttpPayload(method, template, template, null, 200)));
        events.forEach(journal::offer);
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, String thread, long nanos, RuntimeEventPayload payload) {}
}
