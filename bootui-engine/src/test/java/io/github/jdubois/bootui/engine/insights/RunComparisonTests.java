package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ComparabilityFacts;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunComparisonTests {

    private static final RunStart H2 = start(null, "jdbc:h2:mem:shop");

    @Test
    void addedAndGoneStatementsNeverExposeFingerprintLiteralsInPanelOrAgentOutput() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("POST", "/users", 200, 5, sql("insert into old_users(pw) values(\"beforeSecret\")"));
            after.request("POST", "/users", 200, 5, sql("insert into users(pw) values(\"zzsecretzz\")"));
        }

        RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);

        assertThat(comparison.behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .containsExactly("new-statement", "gone-statement");
        assertThat(comparison.behavior())
                .extracting(RuntimeRunChangeDto::detail)
                .containsExactly("insert into users(pw) values(?)", "insert into old_users(pw) values(?)");
        assertThat(comparison.toString()).doesNotContain("zzsecretzz", "beforeSecret", "beforesecret");
        assertThat(RuntimeInsightsAgentView.comparison(comparison).toString())
                .doesNotContain("zzsecretzz", "beforeSecret", "beforesecret");
    }

    @Test
    void literalsThatShareOneDisplayShapeDoNotInventNewOrGoneStatements() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("POST", "/users", 200, 5, sql("insert into users(pw) values(\"beforeSecret\")"));
            after.request("POST", "/users", 200, 5, sql("insert into users(pw) values(\"zzsecretzz\")"));
        }

        assertThat(compare(before, after, H2, H2).behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .doesNotContain("new-statement", "gone-statement");
    }

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
        assertThat(comparison.reason())
                .contains("No comparable route or execution recorded at least 3 samples in both runs");
        assertThat(comparison.behavior()).isEmpty();
        assertThat(comparison.limitations())
                .anySatisfy(limitation ->
                        assertThat(limitation).startsWith("1 route or execution recorded fewer than 3 samples"));
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

    @Test
    void differentSourcesNeverLookLikeAddedOrRemovedWorkOrEdges() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/orders", 200, 5);
            after.request(
                    "GET",
                    "/orders",
                    500,
                    5,
                    sql("select * from orders"),
                    rest("pay.internal"),
                    ai(500, 500),
                    cache("MISS"),
                    new ExceptionPayload("g", "Failure", "sig"));
        }
        RunStart httpOnly = new RunStart(
                null,
                List.of(),
                ComparabilityFacts.of(
                        List.of("dev"),
                        Map.of("dataSource", "jdbc:h2:mem:shop"),
                        null,
                        true,
                        java.util.Set.of(JournalSource.HTTP)));
        RuntimeRunComparisonDto added = compare(before, after, httpOnly, H2);
        RuntimeRunComparisonDto removed = compare(after, before, H2, httpOnly);
        assertThat(added.behavior()).extracting(RuntimeRunChangeDto::kind).containsExactly("status-5xx");
        assertThat(removed.behavior()).extracting(RuntimeRunChangeDto::kind).containsExactly("status-5xx");
        assertThat(added.edges()).isEmpty();
        assertThat(removed.edges()).isEmpty();
        assertThat(added.limitations()).anyMatch(value -> value.contains("only one run recorded"));
    }

    @Test
    void statementsThatDisappearedAreListedButOverflowIsNotMistakenForRemoval() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/orders", 200, 5, sql("select * from orders"));
            after.request("GET", "/orders", 200, 5);
        }
        assertThat(compare(before, after, H2, H2).behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .contains("gone-statement");
        RuntimeEventPayload[] many = new RuntimeEventPayload[65];
        for (int i = 0; i < many.length; i++) {
            many[i] = sql("select * from table_" + i);
        }
        after.request("GET", "/orders", 200, 5, many);
        assertThat(compare(before, after, H2, H2).behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .doesNotContain("gone-statement");
    }

    @Test
    void jobsAndConsumedMessagesCompareWithoutHttpRequests() {
        for (JournalSource source : List.of(JournalSource.SCHEDULED, JournalSource.MESSAGING)) {
            Run before = new Run();
            Run after = new Run();
            for (int i = 0; i < 3; i++) {
                before.execution(source, sql("select * from orders"));
                after.execution(source, sql("select * from orders"), sql("select * from lines"));
            }
            RuntimeRunComparisonDto comparison = compare(before, after, H2, H2);
            assertThat(comparison.current().requests()).isZero();
            assertThat(comparison.status()).isEqualTo(RunComparison.COMPARED);
            assertThat(comparison.behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("new-statement", "statements-per-execution");
            assertThat(comparison.behavior().get(1).sentence()).contains("per execution", "3 and 3 executions");
        }
    }

    @Test
    void executionWordingNeverChangesIdentifiersOrSqlFingerprints() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.execution(JournalSource.SCHEDULED);
            after.execution(JournalSource.SCHEDULED, sql("select `request_id` from `request_log`"));
        }
        assertThat(compare(before, after, H2, H2).behavior())
                .filteredOn(row -> row.kind().equals("new-statement"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.detail()).isEqualTo("select `request_id` from `request_log`");
                    assertThat(row.sentence())
                            .contains("select `request_id` from `request_log`", "executions")
                            .doesNotContain("execution_log", "execution_id");
                });
    }

    @Test
    void executionLatencyNamesWarmExecutionsInsteadOfRequests() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 11; i++) {
            before.execution(JournalSource.SCHEDULED, 5);
            after.execution(JournalSource.SCHEDULED, 50);
        }
        assertThat(compare(before, after, H2, H2).latency()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("warm-p50");
            assertThat(row.sentence()).contains("10 and 10 warm executions").doesNotContain("warm requests");
        });
    }

    @Test
    void oneCacheHitCannotProveThatAStatementDisappeared() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/orders", 200, 5, sql("select * from orders"));
        }
        after.request("GET", "/orders", 200, 5);
        assertThat(compare(before, after, H2, H2).behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .doesNotContain("gone-statement");
    }

    @Test
    void nonAdjacentAndBaselineRestartsAreUnavailableEvenWhenBothHaveTiming() {
        Run before = new Run();
        Run after = new Run();
        RunStart ready = start(1_000_000L, "jdbc:h2:mem:shop");
        assertThat(RunComparison.compare(
                                new RunIdentity("run-5", 5, 1),
                                after.snapshot(),
                                ready,
                                before.summary(2, ready),
                                List.of(),
                                null,
                                null)
                        .restartCost()
                        .reason())
                .contains("adjacent");
        assertThat(RunComparison.compare(
                                new RunIdentity("run-5", 5, 1),
                                after.snapshot(),
                                ready,
                                before.summary(4, ready),
                                List.of(),
                                null,
                                "run-4")
                        .restartCost()
                        .reason())
                .contains("another JVM");
    }

    @Test
    void noEligibleSubjectsNeverSaysComparedAndUnretainableHistoryIsUnavailable() {
        Run empty = new Run();
        assertThat(compare(empty, empty, H2, H2).status()).isEqualTo(RunComparison.INSUFFICIENT);
        assertThat(RunComparison.compare(
                                new RunIdentity("now", 1, 1),
                                empty.snapshot(),
                                H2,
                                null,
                                List.of(),
                                "Holder reloads.",
                                null,
                                true)
                        .status())
                .isEqualTo(RunComparison.UNAVAILABLE);
    }

    @Test
    void allocationUsesMedianSoOneOutlierDoesNotReportARegression() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 5; i++) {
            before.allocated(1024 * 1024);
            after.allocated(i == 4 ? 20 * 1024 * 1024 : 1024 * 1024);
        }
        assertThat(compare(before, after, H2, H2).behavior())
                .extracting(RuntimeRunChangeDto::kind)
                .doesNotContain("allocation-per-request");
        Run increased = new Run();
        for (int i = 0; i < 5; i++) {
            increased.allocated(4 * 1024 * 1024);
        }
        assertThat(compare(before, increased, H2, H2).behavior())
                .singleElement()
                .satisfies(row -> assertThat(row.sentence()).contains("median allocation"));
    }

    @Test
    void disabledSourcePanelsHideChildFactsAndEdgesRatherThanReadingThemAsNoChange() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 4; i++) {
            before.request("GET", "/orders", 200, 5, sql("select * from orders"), cache("MISS"), orm(1, 0, 30));
            after.request(
                    "GET",
                    "/orders",
                    500,
                    5,
                    sql("select * from secret_table"),
                    cache("MISS"),
                    cache("MISS"),
                    orm(1, 3, 600),
                    rest("private.internal"),
                    ai(20_000, 500),
                    new ExceptionPayload("private-group", "private.PrivateException", "private-signature"),
                    new AuthorizationPayload(
                            AuthorizationPayload.REQUEST,
                            null,
                            "private-rule",
                            AuthorizationPayload.ANONYMOUS,
                            true,
                            0));
        }
        Map<String, String> hidden = Map.of(
                BootUiPanels.SQL_TRACE, "disabled",
                BootUiPanels.EXCEPTIONS, "disabled",
                BootUiPanels.CACHE, "disabled",
                BootUiPanels.HIBERNATE, "disabled",
                BootUiPanels.REST_CLIENT_TRACE, "disabled",
                BootUiPanels.AI, "disabled",
                BootUiPanels.SECURITY_LOGS, "disabled");
        RuntimeRunComparisonDto result = policyCompare(before, after, hidden);
        assertThat(result.behavior()).extracting(RuntimeRunChangeDto::kind).containsExactly("status-5xx");
        assertThat(result.edges()).isEmpty();
        for (String panel : hidden.keySet()) {
            if (!panel.equals(BootUiPanels.SECURITY_LOGS)) {
                assertThat(result.limitations()).contains("Facts are not compared because " + panel + " is disabled.");
            }
        }
        assertThat(result.toString())
                .doesNotContain("secret_table", "private.internal", "PrivateException", "private-rule", "anonymous");
    }

    @Test
    void disabledHttpHidesWholeRootsAndRequestTotalsEvenOnEarlyReturns() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 11; i++) {
            before.request("GET", "/private", 200, 5);
            after.request("GET", "/private", 500, 100, sql("select * from private_table"));
        }
        Map<String, String> hidden = Map.of(BootUiPanels.HTTP_EXCHANGES, "disabled");
        RuntimeRunComparisonDto result = policyCompare(before, after, hidden);
        assertThat(result.current().requests()).isZero();
        assertThat(result.status()).isEqualTo(RunComparison.UNAVAILABLE);
        assertThat(result.previous().requests()).isZero();
        assertThat(result.runs()).allSatisfy(run -> assertThat(run.requests()).isZero());
        assertThat(result.behavior()).isEmpty();
        assertThat(result.edges()).isEmpty();
        assertThat(result.latency()).isEmpty();
        assertThat(result.reason()).contains("owning panels are disabled");
        assertThat(result.toString()).doesNotContain("/private", "private_table");
        RunSummary summary = before.summary(4, H2);
        for (RunSummary previous :
                new RunSummary[] {null, before.summary(4, start(null, "jdbc:postgresql://localhost/db"))}) {
            RuntimeRunComparisonDto early = RunComparison.compare(
                    new RunIdentity("run-5", 5, 1),
                    after.snapshot(),
                    H2,
                    previous,
                    List.of(summary.header()),
                    null,
                    null,
                    false,
                    hidden);
            assertThat(early.current().requests()).isZero();
            assertThat(early.runs())
                    .allSatisfy(run -> assertThat(run.requests()).isZero());
            assertThat(early.limitations()).contains("Facts are not compared because http-exchanges is disabled.");
            if (early.previous() != null) {
                assertThat(early.previous().requests()).isZero();
            }
        }
    }

    @Test
    void disabledExecutionPanelsHideWholeJobsAndWebSocketHandlers() {
        for (JournalSource source : List.of(JournalSource.SCHEDULED, JournalSource.WEBSOCKET)) {
            Run before = new Run();
            Run after = new Run();
            for (int i = 0; i < 11; i++) {
                before.execution(source, 5);
                after.execution(source, 100, sql("select * from secret_table"));
            }
            String panel = source == JournalSource.SCHEDULED ? BootUiPanels.SCHEDULED : BootUiPanels.WEBSOCKETS;
            RuntimeRunComparisonDto result = policyCompare(before, after, Map.of(panel, "disabled"));
            assertThat(result.status()).isEqualTo(RunComparison.UNAVAILABLE);
            assertThat(result.behavior()).isEmpty();
            assertThat(result.edges()).isEmpty();
            assertThat(result.latency()).isEmpty();
            assertThat(result.limitations()).contains("Facts are not compared because " + panel + " is disabled.");
            assertThat(result.toString()).doesNotContain("secret_table", "OrderJob.run", "/private-chat");
        }
    }

    @Test
    void aDisabledBrokerHidesItsExecutionsAndHttpPublishEdgesButLeavesOtherBrokersVisible() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            for (String broker : new String[] {"kafka", "rabbitmq", null}) {
                before.message(broker);
                after.message(broker, sql("select * from orders"));
            }
            before.request("GET", "/publish", 200, 5);
            after.request(
                    "GET",
                    "/publish",
                    200,
                    5,
                    new MessagingPayload("kafka", true, "private-topic", false),
                    new MessagingPayload("rabbitmq", true, "visible-queue", false),
                    new WebSocketPayload(
                            "/private-chat", WebSocketPayload.MESSAGE, false, "/private-chat", 0, null, false));
        }
        RuntimeRunComparisonDto result = policyCompare(
                before, after, Map.of(BootUiPanels.KAFKA, "disabled", BootUiPanels.WEBSOCKETS, "disabled"));
        assertThat(result.behavior())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.subject()).startsWith("messaging rabbitmq:"));
        assertThat(result.edges())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.sentence())
                        .doesNotContain("kafka:", "?:", "websocket:", "/private-chat", "private-topic"));
        assertThat(result.edges()).anySatisfy(row -> assertThat(row.sentence()).contains("rabbitmq:visible-queue"));
        assertThat(result.limitations())
                .contains(
                        "Facts are not compared because kafka is disabled.",
                        "Facts are not compared because websockets is disabled.");
    }

    @Test
    void anUnavailableUnusedIntegrationDoesNotClaimItsPanelWasDisabled() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.request("GET", "/orders", 200, 5, sql("select * from orders"));
            after.request("GET", "/orders", 200, 5, sql("select * from orders"));
        }
        assertThat(policyCompare(before, after, Map.of(BootUiPanels.AI, "unavailable"))
                        .limitations())
                .isEmpty();
        after.request("GET", "/orders", 200, 5, ai(20_000, 500));
        RuntimeRunComparisonDto result = policyCompare(before, after, Map.of(BootUiPanels.AI, "unavailable"));
        assertThat(result.limitations()).contains("Facts are not compared because ai is unavailable.");
        assertThat(result.behavior()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }

    private static RuntimeRunComparisonDto policyCompare(Run before, Run after, Map<String, String> hidden) {
        RunSummary previous = before.summary(4, H2);
        return RunComparison.compare(
                new RunIdentity("run-5", 5, 1),
                after.snapshot(),
                H2,
                previous,
                List.of(previous.header()),
                null,
                null,
                false,
                hidden);
    }

    @Test
    void malformedMessagingExecutionIdentitiesAreNotExposedOrMistakenForPanelPolicy() {
        Run before = new Run();
        Run after = new Run();
        for (int i = 0; i < 3; i++) {
            before.message("kafka");
            after.message("kafka");
        }
        AggregatesSnapshot snapshot = after.snapshot();
        JournalAggregates.RouteStats stats = snapshot.executions().get(0).stats();
        for (String name : new String[] {null, "bad", "wrong kafka:private", "messaging "}) {
            JournalAggregates.RouteStats invalid = new JournalAggregates.RouteStats(
                    name,
                    stats.requests(),
                    stats.statusClasses(),
                    stats.latency(),
                    stats.childCounts(),
                    stats.childNanos(),
                    stats.statements(),
                    stats.connectionWaitNanos(),
                    stats.resources(),
                    stats.warmLatency(),
                    stats.cacheMisses(),
                    stats.aiTokens(),
                    stats.authorization(),
                    stats.orm());
            AggregatesSnapshot malformed = new AggregatesSnapshot(
                    snapshot.routes(),
                    snapshot.statements(),
                    snapshot.exceptionGroups(),
                    snapshot.transactionalMethods(),
                    snapshot.threadFamilies(),
                    snapshot.edges(),
                    snapshot.run(),
                    snapshot.overflowed(),
                    List.of(new JournalAggregates.ExecutionStats(JournalSource.MESSAGING, invalid)),
                    true);
            RuntimeRunComparisonDto result = RunComparison.compare(
                    new RunIdentity("run-5", 5, 1),
                    malformed,
                    H2,
                    before.summary(4, H2),
                    List.of(),
                    null,
                    null,
                    false,
                    Map.of());
            assertThat(result.status()).isEqualTo(RunComparison.INSUFFICIENT);
            assertThat(result.behavior()).isEmpty();
            assertThat(result.reason()).doesNotContain("owning panels");
            assertThat(result.limitations())
                    .contains("A messaging execution has an invalid identity and is not compared.");
            assertThat(result.toString()).doesNotContain("wrong kafka:private");
        }
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

        void allocated(long bytes) {
            String requestId = "r" + (++sequence);
            ResourceUsage resources = new ResourceUsage(1L, bytes, 1, 0, null, 0, List.of(), false);
            add(event(
                    requestId,
                    JournalSource.HTTP,
                    1_000_000,
                    new HttpPayload("GET", "/allocated", "/allocated", null, 200, resources)));
            aggregates.onEntries(entries);
            entries.clear();
        }

        void execution(JournalSource source, RuntimeEventPayload... children) {
            execution(source, 1, children);
        }

        void execution(JournalSource source, long millis, RuntimeEventPayload... children) {
            RuntimeEventPayload root =
                    switch (source) {
                        case SCHEDULED -> new ScheduledPayload("OrderJob.run", null);
                        case WEBSOCKET -> WebSocketPayload.handled("/private-chat", "/private-chat", 0L, false);
                        default -> new MessagingPayload("kafka", false, "orders", false);
                    };
            execution(source, millis, root, children);
        }

        void message(String broker, RuntimeEventPayload... children) {
            execution(JournalSource.MESSAGING, 1, new MessagingPayload(broker, false, "orders", false), children);
        }

        private void execution(
                JournalSource source, long millis, RuntimeEventPayload root, RuntimeEventPayload[] children) {
            String id = "e" + (++sequence);
            CorrelationContext context = CorrelationContext.forExecution(id);
            for (RuntimeEventPayload child : children) {
                add(RuntimeEvent.of(source(child), 1000, 1_000_000, context, "worker", null, false, child));
            }
            add(RuntimeEvent.of(source, 1000, millis * 1_000_000, context, "worker", null, false, root));
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
            if (payload instanceof MessagingPayload) {
                return JournalSource.MESSAGING;
            }
            if (payload instanceof WebSocketPayload) {
                return JournalSource.WEBSOCKET;
            }
            if (payload instanceof AuthorizationPayload) {
                return JournalSource.AUTHORIZATION;
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
