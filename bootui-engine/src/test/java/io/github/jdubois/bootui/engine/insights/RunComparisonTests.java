package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ComparabilityFacts;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunComparisonTests {

    private static final RunStart H2 = start(null, "jdbc:h2:mem:shop");

    @Test
    void aQueryAddedToARouteIsReportedWithItsFingerprintAndTheHigherCountAfterThreeRequests() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/api/orders", 200, 5, sql("select * from orders"));
            after.request("GET", "/api/orders", 200, 5, sql("select * from orders"), sql("select * from order_line"));
        }

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.status()).isEqualTo(RunComparison.COMPARED);
        assertThat(comparison.reason()).isNull();
        assertThat(comparison.behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .containsExactly("new-statement", "statements-per-request");
        assertThat(comparison.behavior().get(0).detail()).isEqualTo("select * from order_line");
        assertThat(comparison.behavior().get(1)).satisfies(row -> {
            assertThat(row.change()).isEqualTo("INCREASED");
            assertThat(row.before()).isEqualTo(1.0);
            assertThat(row.after()).isEqualTo(2.0);
            assertThat(row.sentence())
                    .isEqualTo("`GET /api/orders` ran 2.0 statements per request, up from 1.0 in run 4 (3 and 3"
                            + " requests).");
        });
        assertThat(comparison.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.change()).isEqualTo("ADDED");
            assertThat(edge.sentence())
                    .isEqualTo("`GET /api/orders` reads table `order_line`, 3 times, and not in run 4.");
        });
        assertThat(comparison.latency()).isEmpty();
        assertThat(comparison.limitations()).isEmpty();
    }

    @Test
    void fewerThanThreeRequestsOnEachSideIsInsufficientNeverNoChange() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 2; i++) {
            before.request("GET", "/api/orders", 200, 5, sql("select * from orders"));
            after.request("GET", "/api/orders", 200, 5, sql("select * from orders"), sql("select * from orders"));
        }

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.status()).isEqualTo(RunComparison.INSUFFICIENT);
        assertThat(comparison.reason()).contains("No route served at least 3 requests in both runs");
        assertThat(comparison.behavior()).isEmpty();
        assertThat(comparison.limitations())
                .anySatisfy(limitation -> assertThat(limitation).startsWith("1 route served fewer than 3 requests"));
    }

    @Test
    void switchingFromH2ToPostgresqlIsNotComparableWithTheDatabaseFirst() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/api/orders", 200, 5, sql("select * from orders"));
            after.request("GET", "/api/orders", 200, 5, sql("select * from orders"));
        }
        RunStart postgres = start(null, "jdbc:postgresql://localhost:5432/shop");

        RuntimeRunComparisonDto comparison = compare(before, after, H2, postgres);

        assertThat(comparison.status()).isEqualTo(RunComparison.NOT_COMPARABLE);
        assertThat(comparison.reason())
                .isEqualTo("The data sources differ: dataSource jdbc:h2:mem before, dataSource"
                        + " jdbc:postgresql://localhost now.");
        assertThat(comparison.notComparableReasons()).first().isEqualTo(comparison.reason());
        assertThat(comparison.behavior()).isEmpty();
        assertThat(comparison.edges()).isEmpty();
    }

    @Test
    void newRoutesExceptionsCallsStatusesTokensAndCacheMissesAreBehaviorRows() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 4; i++) {
            before.request("GET", "/api/orders", 200, 5, cache("MISS"));
            after.request(
                    "GET",
                    "/api/orders",
                    i < 2 ? 500 : 200,
                    5,
                    cache("MISS"),
                    cache("MISS"),
                    rest("pay.internal:8443"),
                    ai(400, 200),
                    new ExceptionPayload("g1", "java.lang.IllegalStateException", "sig-1"));
        }
        after.request("POST", "/api/refunds", 201, 5);

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .containsExactly(
                        "new-exception",
                        "rest-calls-per-request",
                        "ai-calls-per-request",
                        "cache-misses-per-request",
                        "tokens-per-request",
                        "status-5xx",
                        "route-new");
        assertThat(comparison.behavior().get(0).sentence())
                .isEqualTo("`GET /api/orders` raised `IllegalStateException`, which its 4 requests in run 4 never"
                        + " raised.");
        assertThat(comparison.behavior().get(5).sentence())
                .isEqualTo("`GET /api/orders` answered 50 % of its requests with 5xx, up from 0 % in run 4.");
        assertThat(comparison.behavior().get(6).subject()).isEqualTo("POST /api/refunds");
        assertThat(comparison.edges())
                .extracting(RuntimeRunChangeDto::detail)
                .contains("CALLS HOST pay.internal:8443", "RAISES EXCEPTION_GROUP g1");
    }

    @Test
    void hibernateFlushesAndEntitiesPerRequestAreBehaviorRowsOnlyWhenBothRunsRecordedSessions() {
        Run before = new Run();
        Run after = new Run();
        Run withoutOrm = new Run();
        for (int i = 0; i < 4; i++) {
            before.request("POST", "/api/import", 200, 5, orm(1, 0, 30));
            after.request("POST", "/api/import", 200, 5, orm(1, 3, 600));
            withoutOrm.request("POST", "/api/import", 200, 5);
        }

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .containsExactly("flushes-per-request", "entities-per-request");
        assertThat(comparison.behavior().get(0).sentence())
                .isEqualTo("`POST /api/import` ran 4.0 Hibernate flushes per request, up from 1.0 in run 4 (4 and 4"
                        + " requests).");
        assertThat(comparison.behavior().get(1).sentence())
                .isEqualTo("`POST /api/import` held 600 entities in its persistence context per request, up from 30 in"
                        + " run 4.");
        assertThat(compare(withoutOrm, after, H2, H2).behavior())
                .as("a run that recorded no session never reads as a route that stopped flushing")
                .isEmpty();
    }

    @Test
    void latencyComesLastOnlyWithEnoughWarmSamplesAndALargeShift() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 11; i++) {
            before.request("GET", "/api/slow", 200, i == 0 ? 900 : 10);
            after.request("GET", "/api/slow", 200, 100);
            before.request("GET", "/api/fast", 200, 10);
            after.request("GET", "/api/fast", 200, 15);
        }
        for (int i = 0; i < 10; i++) {
            before.request("GET", "/api/rare", 200, 10);
            after.request("GET", "/api/rare", 200, 200);
        }

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.latency()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("warm-p50");
            assertThat(row.subject()).isEqualTo("GET /api/slow");
            assertThat(row.beforeSamples())
                    .as("the cold first request is left out")
                    .isEqualTo(10);
            assertThat(row.sentence()).contains("Latency on a laptop is noisy");
        });
    }

    @Test
    void aRestartIsComparedWithThePreviousRestartNeverWithAColdStart() {
        Run before = new Run();
        Run after = new Run();
        RunStart slow = start(
                4_000_000_000L,
                "jdbc:h2:mem:shop",
                new StartupStepTiming("spring.beans.instantiate", "orderService", 900_000_000L),
                new StartupStepTiming("spring.beans.instantiate", "catalog", 300_000_000L));
        RunStart fast = start(
                2_000_000_000L,
                "jdbc:h2:mem:shop",
                new StartupStepTiming("spring.beans.instantiate", "orderService", 100_000_000L),
                new StartupStepTiming("spring.beans.instantiate", "catalog", 350_000_000L));

        RuntimeRunComparisonDto restart = RunComparison.compare(
                new RunIdentity("run-5", 5, 1), after.snapshot(), fast, before.summary(4, slow), List.of(), null, null);
        RuntimeRunComparisonDto cold = RunComparison.compare(
                new RunIdentity("run-2", 2, 1), after.snapshot(), fast, before.summary(1, slow), List.of(), null, null);

        assertThat(restart.restartCost().status()).isEqualTo(RunComparison.COMPARED);
        assertThat(restart.restartCost().readyMsBefore()).isEqualTo(4_000.0);
        assertThat(restart.restartCost().readyMsAfter()).isEqualTo(2_000.0);
        assertThat(restart.restartCost().beans()).singleElement().satisfies(bean -> {
            assertThat(bean.subject()).isEqualTo("orderService");
            assertThat(bean.sentence())
                    .isEqualTo("Bean `orderService` took 100 ms to initialize, down from 900 ms in run 4.");
        });
        assertThat(cold.restartCost().status()).isEqualTo(RunComparison.UNAVAILABLE);
        assertThat(cold.restartCost().reason()).contains("never with a cold start");
        assertThat(RunComparison.compare(
                                new RunIdentity("run-5", 5, 1),
                                after.snapshot(),
                                H2,
                                before.summary(4, H2),
                                List.of(),
                                null,
                                null)
                        .restartCost()
                        .reason())
                .contains("no time to ready");
    }

    @Test
    void withoutAPreviousRunTheComparisonSaysWhyAndARunWithoutStartFactsIsALimitation() {
        Run after = new Run();
        after.request("GET", "/api/orders", 200, 5);

        RuntimeRunComparisonDto none = RunComparison.compare(
                new RunIdentity("run-5", 5, 1), after.snapshot(), H2, null, List.of(), "No run is kept.", null);
        RuntimeRunComparisonDto noFacts = RunComparison.compare(
                new RunIdentity("run-5", 5, 1),
                after.snapshot(),
                H2,
                after.summary(4, null),
                List.of(after.summary(4, null).header()),
                null,
                "run-4");

        assertThat(none.status()).isEqualTo(RunComparison.NO_PREVIOUS_RUN);
        assertThat(none.reason()).isEqualTo("No run is kept.");
        assertThat(none.current().source()).isEqualTo("CURRENT");
        assertThat(noFacts.limitations()).first().asString().startsWith("Run 4 recorded no start facts");
        assertThat(noFacts.previous().source()).isEqualTo("BASELINE_FILE");
        assertThat(noFacts.runs())
                .singleElement()
                .satisfies(run -> assertThat(run.ordinal()).isEqualTo(4));
    }

    private static RuntimeRunComparisonDto compare(Run before, Run after, RunStart then, RunStart now) {
        return RunComparison.compare(
                new RunIdentity("run-5", 5, 1), after.snapshot(), now, before.summary(4, then), List.of(), null, null);
    }

    private static RunStart start(Long readyNanos, String url, StartupStepTiming... steps) {
        return new RunStart(
                readyNanos,
                List.of(steps),
                ComparabilityFacts.of(List.of("dev"), Map.of("dataSource", url), null, true, JournalSource.all()));
    }

    private static SqlPayload sql(String sql) {
        return new SqlPayload(sql, null, "dataSource", false);
    }

    private static CachePayload cache(String operation) {
        return new CachePayload("prices", operation, null);
    }

    private static RestClientPayload rest(String authority) {
        return new RestClientPayload("POST", authority, "/pay", 200, "RestClient", false);
    }

    private static OrmPayload orm(int flushes, int autoFlushes, int entities) {
        return new OrmPayload(null, 3, 1_000_000, 1, 0, flushes, 100_000, autoFlushes, 100_000, 0, entities, 0, 0, 0);
    }

    private static AiPayload ai(long input, long output) {
        return new AiPayload("chat", "openai", "gpt-4o", input, output, "stop", false);
    }

    /** One run's aggregates, fed request by request, children first as recorders publish them. */
    private static final class Run {

        private final JournalAggregates aggregates = new JournalAggregates();
        private final List<JournalEntry> entries = new ArrayList<>();
        private long sequence;

        void request(String method, String route, int status, long millis, RuntimeEventPayload... children) {
            String requestId = "r" + (++sequence);
            for (RuntimeEventPayload child : children) {
                add(event(requestId, source(child), 1_000_000, child));
            }
            add(event(
                    requestId,
                    JournalSource.HTTP,
                    millis * 1_000_000,
                    new HttpPayload(method, route, route, null, status)));
            aggregates.onEntries(entries);
            entries.clear();
        }

        AggregatesSnapshot snapshot() {
            return aggregates.snapshot();
        }

        RunSummary summary(int ordinal, RunStart start) {
            return RunSummary.of(new RunIdentity("run-" + ordinal, ordinal, 1), snapshot(), start, 2);
        }

        private void add(RuntimeEvent event) {
            entries.add(new JournalEntry(++sequence, event, event.estimatedBytes()));
        }

        private static JournalSource source(RuntimeEventPayload payload) {
            if (payload instanceof SqlPayload) {
                return JournalSource.SQL;
            }
            if (payload instanceof CachePayload) {
                return JournalSource.CACHE;
            }
            if (payload instanceof RestClientPayload) {
                return JournalSource.REST_CLIENT;
            }
            if (payload instanceof AiPayload) {
                return JournalSource.AI;
            }
            if (payload instanceof OrmPayload) {
                return JournalSource.ORM;
            }
            return JournalSource.EXCEPTION;
        }

        private static RuntimeEvent event(
                String requestId, JournalSource source, long nanos, RuntimeEventPayload payload) {
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
    }
}
