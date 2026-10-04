package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeInsightsServiceTests {

    private RuntimeJournal journal = journal(JournalSource.all());
    private int requests;

    @Test
    void anEmptySnapshotLeavesEveryCheckWithoutEligibleWork() {
        assertThat(service().report().checks()).isNotEmpty().allSatisfy(check -> {
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.status()).isNotIn("EVALUATED", "PARTIAL");
        });
    }

    @Test
    void aZeroEligibleCheckPreservesTheExplanationOfUncountedWork() {
        Observation observation = new Observation() {
            @Override
            public String kind() {
                return "uncounted-work";
            }

            @Override
            public String title() {
                return "Uncounted work";
            }

            @Override
            public CorrelationTier minimumTier() {
                return CorrelationTier.REQUEST_ID;
            }

            @Override
            public Set<JournalSource> reads() {
                return Set.of(JournalSource.HTTP);
            }

            @Override
            public Evaluation evaluate(InsightsSnapshot snapshot) {
                return new Evaluation(0, List.of(), "Recorded work could not be placed against its transaction.");
            }
        };
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> true, InsightsStack.SPRING_MVC, List::of, List.of(observation));
        assertThat(service.report().checks()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("INSUFFICIENT");
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.reason())
                    .contains(
                            "No eligible work was recorded",
                            "Recorded work could not be placed against its transaction.");
        });
    }

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void repeatedSelectsAfterAParentStatementAreObservedOnceThreeRequestsShowThem() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select * from orders where id = 1", 6, "select * from lines where order_id = ?"));
        }
        request(
                "GET",
                "/api/orders/{id}",
                sqls("select * from orders where id = 2", 2, "select * from lines where order_id = ?"));
        // Counterexample: a loop with no parent statement before it.
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/batch", sqls(null, 6, "select * from items where id = ?"));
        }
        // Insufficient: one request repeats on another route.
        request(
                "GET",
                "/api/customers/{id}",
                sqls("select * from customers where id = 7", 5, "select * from notes where customer_id = ?"));

        RuntimeInsightsReportDto report = service().report();

        Map<String, RuntimeObservationDto> byRoute = observations(report, RepeatedSelects.KIND);
        assertThat(byRoute).containsOnlyKeys("GET /api/orders/{id}", "GET /api/customers/{id}");
        RuntimeObservationDto orders = byRoute.get("GET /api/orders/{id}");
        assertThat(orders.status()).isEqualTo("OBSERVED");
        assertThat(orders.eligible()).isEqualTo(4);
        assertThat(orders.affected()).isEqualTo(3);
        assertThat(orders.sentence())
                .contains("`GET /api/orders/{id}` ran `select * from lines where order_id = ?` 5 or more times")
                .contains("3 of 4 requests, up to 6 times in one.");
        assertThat(orders.minimumTier()).isEqualTo("REQUEST_ID");
        assertThat(orders.exemplarRequestIds()).hasSize(3);
        assertThat(orders.id()).matches("repeated-selects:[0-9a-f]{10}");
        assertThat(byRoute.get("GET /api/customers/{id}").status()).isEqualTo("INSUFFICIENT");
        assertThat(byRoute.get("GET /api/customers/{id}").sentence()).contains("1 of 3 requests needed");
        assertThat(report.observations().get(0).status())
                .as("sufficient findings first")
                .isEqualTo("OBSERVED");
    }

    @Test
    void anObservationsIdIsStableAcrossRefreshesAndItsDetailListsItsEvidence() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        }
        RuntimeInsightsService service = service();
        String id = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();

        request("GET", "/api/health", sqls(null, 0, null));
        String again = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();
        RuntimeObservationDetailDto detail = service.insight(id);

        assertThat(again).isEqualTo(id).matches("repeated-selects:[0-9a-f]{10}");
        assertThat(detail.available()).isTrue();
        assertThat(detail.columns()).containsExactly("Request", "Executions", "Time (ms)", "Call site");
        assertThat(detail.rows())
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.cells().get(1)).isEqualTo("5"));
        assertThat(detail.truncated()).isZero();
        assertThat(service.insight("repeated-selects:nothing").available()).isFalse();
    }

    @Test
    void clearingTheRecordingInvalidatesTheCachedReportEvenWithoutAnyNewEvent() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        }
        RuntimeInsightsService service = service();
        String id = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();
        assertThat(service.insight(id).available()).isTrue();

        journal.clear();

        assertThat(observations(service.report(), RepeatedSelects.KIND)).isEmpty();
        assertThat(service.report().window().retainedEvents()).isZero();
        assertThat(service.insight(id).available()).isFalse();
    }

    @Test
    void aReportReadWhileAClearIsStillRunningIsNotCachedAsTheClearedOne() throws Exception {
        request("GET", "/api/orders/{id}", sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        RuntimeInsightsService service = service();
        service.report();
        java.util.concurrent.CountDownLatch inClear = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        journal.addListener(new io.github.jdubois.bootui.engine.journal.JournalListener() {
            @Override
            public void onEntries(List<io.github.jdubois.bootui.engine.journal.JournalEntry> entries) {}

            @Override
            public void onClear() {
                inClear.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread clearer = new Thread(journal::clear);
        clearer.start();
        assertThat(inClear.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        service.report();
        release.countDown();
        clearer.join();

        assertThat(service.report().window().retainedEvents()).isZero();
        assertThat(journal.status().clears()).isEqualTo(1);
    }

    @Test
    void writesInGetRequestsAreAskedAboutAndWritesInPostsOrFailedWritesAreNot() {
        request("GET", "/api/products/{id}", sqls("update product_views set n = n + 1 where id = 3", 0, null));
        request("POST", "/api/orders", sqls("insert into orders (id) values (5)", 0, null));
        request("GET", "/api/cart", failedWrite("delete from carts where id = 9"));

        Map<String, RuntimeObservationDto> byRoute = observations(service().report(), SafeMethodDml.KIND);

        assertThat(byRoute).containsOnlyKeys("GET /api/products/{id}");
        RuntimeObservationDto views = byRoute.get("GET /api/products/{id}");
        assertThat(views.status()).isEqualTo("OBSERVED");
        assertThat(views.sentence())
                .contains("executed `update product_views set n = n + ? where id = ?` in 1 of 1 request")
                .endsWith("or a change the caller asked for?");
    }

    @Test
    void aQuarkusPreparationCannotProveAWriteWhenTheHibernatePanelIsHidden() {
        request(
                "GET",
                "/api/products/{id}",
                new Child(JournalSource.SQL, 0, new SqlPayload("insert into audit (id) values (1)", null, "db", false)),
                new Child(JournalSource.ORM, 1_000, orm()));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.QUARKUS, null)
                .report();

        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason())
                .contains("hibernate")
                .contains("prepared statements cannot be verified");
        assertThat(observations(report, SafeMethodDml.KIND)).isEmpty();
    }

    @Test
    void aTimedJdbcWriteIsStillReportedWhenHibernateIsUnavailable() {
        request(
                "GET",
                "/api/products/{id}",
                new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload("insert into audit (id) values (1)", null, "db", false)));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.QUARKUS, null)
                .report();

        assertThat(observations(report, SafeMethodDml.KIND)).containsOnlyKeys("GET /api/products/{id}");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).contains("timed JDBC executions still are");
    }

    @Test
    void aSentenceAndItsEvidenceQuoteAStatementsShapeNeverAValueItsFingerprintKeeps() {
        // A fingerprint keeps identifier-like "..." runs, which MySQL reads as string literals, and a truncated dollar
        // quote verbatim: it groups statements but is never shown.
        request("GET", "/api/products/{id}", sqls("insert into users(pw) values(\"hunter2\")", 0, null));
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/notes/{id}",
                    sqls("select 1 from notes", 5, "select * from t where body = $$sk_live_EXAMPLE"));
        }
        RuntimeInsightsService service = service();
        RuntimeInsightsReportDto report = service.report();

        RuntimeObservationDto write = observations(report, SafeMethodDml.KIND).get("GET /api/products/{id}");
        RuntimeObservationDto repeat =
                observations(report, RepeatedSelects.KIND).get("GET /api/notes/{id}");
        assertThat(write.sentence()).contains("executed `insert into users(pw) values(?)`");
        assertThat(repeat.sentence()).contains("ran `select * from t where body = ?`");
        for (RuntimeObservationDto observation : report.observations()) {
            assertThat(observation.sentence()).doesNotContain("hunter2", "sk_live_EXAMPLE");
            RuntimeObservationDetailDto detail = service.insight(observation.id());
            assertThat(detail.rows())
                    .allSatisfy(row ->
                            assertThat(String.join(" ", row.cells())).doesNotContain("hunter2", "sk_live_EXAMPLE"));
        }
    }

    @Test
    void connectionsHeldTogetherAreFoundAndBackToBackConnectionsAreNot() {
        // Nested: an inner connection checked out and released while the outer one is held.
        request("POST", "/api/orders", connection("db", 1_000, 50_000_000), connection("db", 10_000_000, 5_000_000));
        // Sequential: the second checked out exactly when the first was released.
        request("POST", "/api/payments", connection("db", 0, 10_000_000), connection("db", 10_000_000, 10_000_000));
        // Two data sources at once are two pools, each holding one.
        request("POST", "/api/report", connection("db", 0, 10_000_000), connection("audit", 1_000, 5_000_000));

        RuntimeInsightsService insights = service();
        insights.setPoolSizes(name -> name.equals("db") ? 10 : null);
        Map<String, RuntimeObservationDto> byRoute = observations(insights.report(), ConnectionsPerRequest.KIND);

        assertThat(byRoute).containsOnlyKeys("POST /api/orders");
        assertThat(byRoute.get("POST /api/orders").sentence())
                .isEqualTo("`POST /api/orders` held 2 connections of `db` at the same time in 1 of 1 request.");
        assertThat(byRoute.get("POST /api/orders").whatToCheck().get(1))
                .contains("pool of 10 connections", "10 concurrent requests", "estimate", "not proof");
        assertThat(observations(service().report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("floor((P - 1) / 1) + 1");
    }

    @Test
    void connectionEstimateRoundsUpWithThreeHeldAndOneRequestCanExhaustASmallerPool() {
        request(
                "POST",
                "/api/orders",
                connection("db", 0, 30_000_000),
                connection("db", 1_000, 20_000_000),
                connection("db", 2_000, 10_000_000));
        RuntimeInsightsService insights = service();
        insights.setPoolSizes(name -> 10);
        assertThat(observations(insights.report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("5 concurrent requests");
        RuntimeInsightsService small = service();
        small.setPoolSizes(name -> 1);
        assertThat(observations(small.report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("1 concurrent request can");
    }

    @Test
    void aCheckWhoseSourceIsNotRecordedOrWhosePanelIsDisabledIsNotApplicableWithItsReason() {
        journal.close();
        journal = journal(EnumSet.of(JournalSource.HTTP, JournalSource.SQL));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SQL_TRACE), null, null)
                .report();

        Map<String, RuntimeInsightCheckDto> checks =
                report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
        assertThat(checks.get(ConnectionsPerRequest.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(ConnectionsPerRequest.KIND).reason()).contains("does not record the connection source");
        assertThat(checks.get(RepeatedSelects.KIND).reason()).contains("panel, whose evidence this reads, is disabled");
        assertThat(report.observations()).isEmpty();
    }

    @Test
    void theReportStatesItsWindowAndCoverageAndADisabledJournalSaysWhy() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1,
                1,
                CorrelationContext.NONE,
                "pool-1",
                null,
                false,
                new SqlPayload("select 2", null, "db", false)));
        drain();

        RuntimeInsightsReportDto report = service().report();

        assertThat(report.available()).isTrue();
        assertThat(report.window().requests()).isEqualTo(1);
        assertThat(report.window().runId()).isEqualTo(journal.run().id());
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("sql"))
                .singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.byRequestId()).isEqualTo(1);
                    assertThat(coverage.unlinked()).isEqualTo(1);
                });
        assertThat(report.checks())
                .filteredOn(check -> !check.kind().equals(AiUsageByRoute.KIND)
                        && !check.kind().equals(ProxyBypass.KIND)
                        && !check.kind().equals(WorkAfterResponse.KIND))
                .allSatisfy(check -> {
                    assertThat(check.status()).as(check.kind()).isIn("INSUFFICIENT", "EVALUATED");
                    if ("INSUFFICIENT".equals(check.status())) {
                        assertThat(check.eligibleRequests()).isZero();
                        assertThat(check.reason()).contains("No eligible work");
                    } else if (!HeapGrowthAfterGc.KIND.equals(check.kind())) {
                        assertThat(check.eligibleRequests()).isPositive();
                    }
                });
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(WorkAfterResponse.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without the BootUI agent, work handed to an executor is unseen")
                .containsExactly("NOT_APPLICABLE");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without a resolver of proxy boundaries, a bypass cannot be judged")
                .containsExactly("NOT_APPLICABLE");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AiUsageByRoute.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without tracing, AI usage is unknown rather than none")
                .containsExactly("NOT_APPLICABLE");
        assertThat(new RuntimeInsightsService(null, null, null, null, null)
                        .report()
                        .unavailableReason())
                .isEqualTo(RuntimeInsightsService.DISABLED);
    }

    @Test
    void checksThatReadSqlAreUnavailableWhereThisApplicationsSqlIsNotRecorded() {
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/orders/{id}");
        }
        RuntimeInsightsService service = service();
        service.setSqlCapture(() -> SqlCapture.of(null, true, true));

        RuntimeInsightsReportDto report = service.report();

        Map<String, RuntimeInsightCheckDto> checks =
                report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
        for (String kind : new String[] {RepeatedSelects.KIND, SafeMethodDml.KIND, ConnectionsPerRequest.KIND}) {
            assertThat(checks.get(kind).status()).as(kind).isEqualTo("UNAVAILABLE");
            assertThat(checks.get(kind).reason()).as(kind).isEqualTo(SqlCapture.R2DBC_ONLY);
            assertThat(checks.get(kind).findings()).isZero();
        }
        assertThat(SqlCapture.R2DBC_ONLY)
                .contains("BootUI records JDBC statements")
                .contains("not R2DBC");
        RuntimeInsightCheckDto breakdown = checks.get(RouteTimeBreakdown.KIND);
        assertThat(breakdown.status())
                .as("without request phase measurements, no breakdown is eligible")
                .isEqualTo("INSUFFICIENT");
        assertThat(breakdown.eligibleRequests()).isZero();
        assertThat(breakdown.reason()).contains("No eligible work", SqlCapture.R2DBC_ONLY);
        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .allSatisfy(observation -> {
                    assertThat(observation.status()).isEqualTo("INSUFFICIENT");
                    assertThat(observation.eligible()).isZero();
                    assertThat(observation.limitations())
                            .anySatisfy(limitation -> assertThat(limitation).contains(SqlCapture.R2DBC_ONLY));
                });

        service.setSqlCapture(SqlCapture::capturing);
        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(RepeatedSelects.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("the requests remain eligible for SELECT checks once SQL capture is available")
                .containsExactly("EVALUATED");
    }

    @Test
    void sqlCaptureNamesWhyStatementsAreNotRecorded() {
        SqlTraceRecorder disabled = new SqlTraceRecorder(false, true, false, false, 8, 100, 2000, 200, 5);
        SqlTraceRecorder unwrapped = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
        SqlTraceRecorder wrapped = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
        wrapped.registerDataSource("dataSource");

        assertThat(SqlCapture.of(null, true, false)).isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        assertThat(SqlCapture.of(disabled, true, false)).isEqualTo(SqlCapture.notRecorded(SqlCapture.DISABLED));
        assertThat(SqlCapture.of(unwrapped, true, true)).isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
        assertThat(SqlCapture.of(unwrapped, false, false))
                .as("on Quarkus, the recorder exists only with a JDBC data source")
                .isEqualTo(SqlCapture.capturing());
        assertThat(SqlCapture.of(wrapped, true, false)).isEqualTo(SqlCapture.capturing());
        assertThat(SqlCapture.of(wrapped, true, true))
                .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
    }

    @Test
    void aLiveExposureSwitchReProjectsTheQuotedTemplateAtAnUnchangedWatermark() {
        request(
                "GET",
                "/api/login",
                new Child(
                        JournalSource.LOG,
                        -1,
                        new LogPayload(
                                "org.springframework.web.Login", "WARN", "Login password=hunter2 refused", null)));
        ValueExposure[] exposure = {ValueExposure.FULL};
        RuntimeInsightsService service = service();
        service.setExposure(new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure[0];
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        });

        RuntimeObservationDto full =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");
        exposure[0] = ValueExposure.MASKED;
        RuntimeObservationDto masked =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");
        exposure[0] = ValueExposure.METADATA_ONLY;
        RuntimeObservationDto metadata =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");

        assertThat(full.sentence()).contains("password=hunter2");
        assertThat(masked.sentence()).contains("password=").doesNotContain("hunter2");
        assertThat(metadata.sentence()).doesNotContain("password", "hunter2", "refused");
        assertThat(metadata.id()).isEqualTo(full.id());
        assertThat(service.insight(metadata.id()).toString()).doesNotContain("hunter2");
    }

    @Test
    void aNullExposurePolicyFailsClosedToMasked() {
        request(
                "GET",
                "/api/login",
                new Child(
                        JournalSource.LOG,
                        -1,
                        new LogPayload(
                                "org.springframework.web.Login", "WARN", "Login password=hunter2 refused", null)));
        RuntimeInsightsService service = service();
        service.setExposure(null);

        assertThat(observations(service.report(), FrameworkWarningsByRoute.KIND)
                        .get("GET /api/login")
                        .sentence())
                .doesNotContain("hunter2");
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, null, null);
    }

    private static Map<String, RuntimeObservationDto> observations(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .collect(Collectors.toMap(RuntimeObservationDto::subject, Function.identity()));
    }

    /** A parent statement, then {@code repeats} executions of {@code repeated}. */
    private static Child[] sqls(String parent, int repeats, String repeated) {
        Child[] children = new Child[(parent == null ? 0 : 1) + repeats];
        int i = 0;
        if (parent != null) {
            children[i++] =
                    new Child(JournalSource.SQL, 1_000_000, new SqlPayload(parent, "Parent.load:10", "db", false));
        }
        for (int r = 0; r < repeats; r++) {
            children[i++] =
                    new Child(JournalSource.SQL, 1_000_000, new SqlPayload(repeated, "Child.load:20", "db", false));
        }
        return children;
    }

    private static Child[] failedWrite(String sql) {
        return new Child[] {new Child(JournalSource.SQL, 1_000_000, new SqlPayload(sql, null, "db", true))};
    }

    private static Child connection(String dataSource, long checkoutNanos, long heldNanos) {
        return new Child(JournalSource.CONNECTION, heldNanos, new ConnectionPayload(dataSource, 0, 1, checkoutNanos));
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
        drain();
    }

    private void drain() {
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static RuntimeJournal journal(Set<JournalSource> sources) {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, sources), RunIdentity.start());
    }

    @Test
    void aCheckReadingASourceV2AddedIsNotApplicableWhenTheOwningPanelIsDisabled() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", "alice", "hasRole", "USER", true, 1)),
                new Child(JournalSource.ORM, 1_000, orm()));

        Map<String, RuntimeInsightCheckDto> checks = checks(new RuntimeInsightsService(
                        journal,
                        null,
                        panel -> !panel.equals(BootUiPanels.SECURITY_LOGS) && !panel.equals(BootUiPanels.HIBERNATE),
                        null,
                        null)
                .report());

        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).reason())
                .isEqualTo("The security-logs panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(OrmAutoFlush.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(OrmAutoFlush.KIND).reason())
                .isEqualTo("The hibernate panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .as("a check that only reads the source as optional evidence says it is not counted")
                .contains("The security-logs panel is disabled, so its evidence is not counted.")
                .contains("The hibernate panel is disabled, so its evidence is not counted.");
    }

    @Test
    void aPanelTheApplicationCannotServeIsReportedUnavailableRatherThanDisabled() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", "alice", "hasRole", "USER", true, 1)),
                new Child(JournalSource.ORM, 1_000, orm()));

        RuntimeInsightsService insights = new RuntimeInsightsService(
                journal,
                null,
                panel -> !panel.equals(BootUiPanels.SECURITY_LOGS) && !panel.equals(BootUiPanels.HIBERNATE),
                null,
                null);
        insights.setPanelUnavailable(panel -> panel.equals(BootUiPanels.SECURITY_LOGS)
                ? "Quarkus security events are disabled. Set quarkus.security.events.enabled=true."
                : null);
        Map<String, RuntimeInsightCheckDto> checks = checks(insights.report());

        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).reason())
                .as("an unavailable panel names what would make it available instead of implying someone switched"
                        + " it off")
                .isEqualTo("The security-logs panel, whose evidence this reads, is not available in this"
                        + " application: Quarkus security events are disabled. Set"
                        + " quarkus.security.events.enabled=true.");
        assertThat(checks.get(OrmAutoFlush.KIND).reason())
                .as("a panel off with no reason is still simply disabled")
                .isEqualTo("The hibernate panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .as("optional evidence makes the same distinction rather than reporting it as insufficient")
                .contains("The security-logs panel is not available in this application, so its evidence is not"
                        + " counted: Quarkus security events are disabled. Set"
                        + " quarkus.security.events.enabled=true.")
                .contains("The hibernate panel is disabled, so its evidence is not counted.");
    }

    @Test
    void anUnavailablePanelsEvidenceStaysOutOfTheProjection() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", null, "authenticated", null, true, 1)));

        RuntimeInsightsService insights = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals(BootUiPanels.SECURITY_LOGS), null, null);
        insights.setPanelUnavailable(panel -> panel.equals(BootUiPanels.SECURITY_LOGS) ? "the reason" : null);

        assertThat(checks(insights.report())
                        .get(AnonymousSuccessOnRestrictedRoute.KIND)
                        .status())
                .as("naming why a panel is off never makes evidence it cannot serve visible")
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void disablingOneBrokersPanelLeavesTheOthersCountedAndIsStillReported() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));

        Map<String, RuntimeInsightCheckDto> checks =
                checks(new RuntimeInsightsService(journal, null, panel -> !panel.equals(BootUiPanels.KAFKA), null, null)
                        .report());

        assertThat(checks.get(RouteTimeBreakdown.KIND).status())
                .as("the rabbitmq and jms panels still serve their evidence")
                .isNotEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .contains("The kafka panel is disabled, so its evidence is not counted.");
    }

    @Test
    void aUnitOfWorkIsLeftOutWholeWhenThePanelThatOpensItIsDisabled() {
        request("GET", "/api/orders/{id}", sqls("select * from orders where id = 1", 0, null));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();

        Map<String, Long> events = report.coverage().stream()
                .collect(Collectors.toMap(RuntimeInsightCoverageDto::source, RuntimeInsightCoverageDto::events));
        assertThat(events.getOrDefault("http", 0L))
                .as("the disabled panel's own evidence, including the route and its status, is left out")
                .isZero();
        assertThat(events.getOrDefault("sql", 0L))
                .as("and so is the rest of the request it opened, which names that route too")
                .isZero();
        assertThat(report.observations())
                .as("nothing reports a route the http-exchanges panel no longer publishes")
                .noneMatch(observation -> String.valueOf(observation.subject()).contains("/api/orders"));
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("http-exchanges");
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void aSurvivingScheduledRunDoesNotMakeHiddenHttpRequestsLookEvaluated() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();

        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).contains("http-exchanges");
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(RepeatedSelects.KIND).eligibleRequests()).isEqualTo(1);
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("http-exchanges");
    }

    @Test
    void aHiddenScheduledUnitDoesNotMakeSqlChecksLookEvaluated() {
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SCHEDULED), null, null)
                .report();
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("scheduled");
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).isNull();
    }

    @Test
    void hiddenScheduledUnitsDoNotLimitFindingsAboutVisibleHttpRequests() {
        request("GET", "/api/orders/{id}", sqls("insert into audit (id) values (1)", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SCHEDULED), null, null)
                .report();
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).isNull();
        assertThat(observations(report, SafeMethodDml.KIND))
                .containsOnlyKeys("GET /api/orders/{id}")
                .allSatisfy((route, finding) ->
                        assertThat(finding.limitations()).noneMatch(limitation -> limitation.contains("scheduled")));
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("scheduled");
    }

    @Test
    void anHttpDropIsCountedOnceWhenTheObservationRequiresHttp() throws InterruptedException {
        journal.close();
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 1, 10, 10, JournalSource.all()),
                RunIdentity.start());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            blocked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            1_000,
                            1_000,
                            CorrelationContext.forRequest("r1"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/first", "/first", null, 200))))
                    .isTrue();
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            2_000,
                            1_000,
                            CorrelationContext.forRequest("r2"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/second", "/second", null, 200))))
                    .isTrue();
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            3_000,
                            1_000,
                            CorrelationContext.forRequest("r3"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/dropped", "/dropped", null, 200))))
                    .isFalse();
        } finally {
            release.countDown();
        }
        drain();

        assertThat(journal.status().dropped()).containsEntry(JournalSource.HTTP, 1L);
        RuntimeInsightCheckDto check = checks(service().report()).get(SafeMethodDml.KIND);
        assertThat(check.status()).isEqualTo("PARTIAL");
        assertThat(check.reason()).contains("dropped 1 events").doesNotContain("dropped 2 events");
    }

    @Test
    void unavailablePanelsWithoutHiddenUnitsDoNotChangeChecks() {
        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal,
                        null,
                        panel -> !panel.equals(BootUiPanels.JMS) && !panel.equals(BootUiPanels.SCHEDULED),
                        InsightsStack.QUARKUS,
                        null)
                .report();
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).isNull();
    }

    @Test
    void aTraceOnlyAiCallOwnedByAHiddenHttpRequestIsNotUncorrelatedCoverage() {
        journal.offer(new RuntimeEvent(
                JournalSource.HTTP,
                1_000,
                200_000_000,
                "hidden",
                null,
                "trace-hidden",
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/hidden", "/hidden", null, 200)));
        journal.offer(new RuntimeEvent(
                JournalSource.AI,
                1_010,
                1_000,
                null,
                null,
                "trace-hidden",
                "http-1",
                null,
                false,
                new AiPayload(AiPayload.CHAT, "openai", "model", 10L, 5L, "stop", false)));
        journal.offer(new RuntimeEvent(
                JournalSource.AI,
                1_010,
                1_000,
                null,
                null,
                "unowned-trace",
                "http-1",
                null,
                false,
                new AiPayload(AiPayload.CHAT, "openai", "model", 10L, 5L, "stop", false)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("ai"))
                .singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.events()).isEqualTo(1);
                    assertThat(coverage.unlinked()).isEqualTo(1);
                });
    }

    @Test
    void onePanelReadStandsForAWholeProjectionSoAToggleCannotMakeEvidenceLookAbsent() {
        request("GET", "/api/orders/{id}", sqls("select * from orders where id = 1", 0, null));
        Map<String, Integer> reads = new LinkedHashMap<>();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal,
                        null,
                        // A panel toggled between two reads of the same projection would let it drop a source's
                        // events and then evaluate as though the source were visible, which reads as absence.
                        panel -> reads.merge(panel, 1, Integer::sum) == 1,
                        null,
                        null)
                .report();

        assertThat(reads.values()).as("every owning panel is read exactly once").containsOnly(1);
        assertThat(report.checks()).isNotEmpty();
    }

    private static Map<String, RuntimeInsightCheckDto> checks(RuntimeInsightsReportDto report) {
        return report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
    }

    private static OrmPayload orm() {
        return new OrmPayload("default", 1, 1_000, 1, 1_000, 1, 1_000, 0, 0, 1, 1, 0, 0, 0, List.of());
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
