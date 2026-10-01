package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeInsightsServiceTests {

    private RuntimeJournal journal = journal(JournalSource.all());
    private int requests;

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
        assertThat(orders.id()).startsWith("repeated-selects:GET /api/orders/{id}:");
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

        assertThat(again).isEqualTo(id);
        assertThat(detail.available()).isTrue();
        assertThat(detail.columns()).containsExactly("Request", "Executions", "Time (ms)", "Call site");
        assertThat(detail.rows())
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.cells().get(1)).isEqualTo("5"));
        assertThat(detail.truncated()).isZero();
        assertThat(service.insight("repeated-selects:nothing").available()).isFalse();
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
    void connectionsHeldTogetherAreFoundAndBackToBackConnectionsAreNot() {
        // Nested: an inner connection checked out and released while the outer one is held.
        request("POST", "/api/orders", connection("db", 1_000, 50_000_000), connection("db", 10_000_000, 5_000_000));
        // Sequential: the second checked out exactly when the first was released.
        request("POST", "/api/payments", connection("db", 0, 10_000_000), connection("db", 10_000_000, 10_000_000));
        // Two data sources at once are two pools, each holding one.
        request("POST", "/api/report", connection("db", 0, 10_000_000), connection("audit", 1_000, 5_000_000));

        Map<String, RuntimeObservationDto> byRoute = observations(service().report(), ConnectionsPerRequest.KIND);

        assertThat(byRoute).containsOnlyKeys("POST /api/orders");
        assertThat(byRoute.get("POST /api/orders").sentence())
                .isEqualTo("`POST /api/orders` held 2 connections of `db` at the same time in 1 of 1 request.");
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
        assertThat(report.checks()).extracting(RuntimeInsightCheckDto::status).containsOnly("EVALUATED");
        assertThat(new RuntimeInsightsService(null, null, null, null, null)
                        .report()
                        .unavailableReason())
                .isEqualTo(RuntimeInsightsService.DISABLED);
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

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
