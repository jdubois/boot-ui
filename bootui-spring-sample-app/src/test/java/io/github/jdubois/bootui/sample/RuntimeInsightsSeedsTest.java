package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The sample's Runtime Insights seeds ({@code docs/PLAN-v2.md} M3-6): each seeded route is reported by its observation,
 * and each counterexample beside it is not. This is the honesty gate's sample-level check on Spring MVC.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_insight_seeds;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/insight-seeds/application-bootui.properties"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeInsightsSeedsTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @LocalServerPort
    int port;

    @Autowired
    RuntimeJournal journal;

    private List<JsonNode> observations;

    @BeforeAll
    void seed() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/insights/orders").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/orders/joined").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/orders/report").status()).isEqualTo(200);
            assertThat(probe.get("/api/sample/products").status()).isEqualTo(200);
        }
        assertThat(probe.get("/api/insights/orders/1").status()).isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/2/confirm", JSON).status()).isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/3/ship", JSON).status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/orders/4/price-check").status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/orders/4/price-check-after-commit").status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/5/recalculate", JSON).status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/5/recalculate-through-bean", JSON)
                        .status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/6/import", JSON).status()).isEqualTo(200);
        assertThat(probe.request("POST", "/api/insights/orders", JSON, "{not json")
                        .status())
                .isEqualTo(400);
        assertThat(probe.post("/api/insights/debug/reset-totals", JSON).status())
                .isEqualTo(200);
        assertThat(probe.get("/api/insights/reports/payroll").status()).isEqualTo(403);
        assertThat(probe.get("/api/insights/reports/PAYROLL").status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/reports/summary").status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        observations = new ArrayList<>();
        probe.get("/bootui/api/runtime-insights").json().path("observations").forEach(observations::add);
    }

    @Test
    void repeatedSelectsAreFoundOnTheLineByLineLoopButNotOnTheJoin() {
        assertThat(subjects("repeated-selects", "OBSERVED"))
                .contains("GET /api/insights/orders")
                .doesNotContain("GET /api/insights/orders/joined", "GET /api/sample/products");
    }

    @Test
    void aGetThatWritesIsAskedAboutButThePublicCatalogReadIsNot() {
        assertThat(subjects("safe-method-dml", "OBSERVED"))
                .contains("GET /api/insights/orders/{id}")
                .doesNotContain("GET /api/sample/products", "GET /api/insights/orders");
    }

    @Test
    void aRequiresNewWriteHoldsTwoConnectionsAndSplitsTheWritesUnlikeOneTransaction() {
        assertThat(subjects("connections-per-request", null))
                .contains("POST /api/insights/orders/{id}/confirm")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
        assertThat(subjects("split-transaction-writes", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/confirm")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
    }

    @Test
    void aRemoteCallInsideTheTransactionIsFoundButNotOneAfterItsCommit() {
        assertThat(subjects("transaction-across-remote-call", null))
                .contains("GET /api/insights/orders/{id}/price-check")
                .doesNotContain("GET /api/insights/orders/{id}/price-check-after-commit");
    }

    @Test
    void aSelfInvocationBypassesTheProxyButACallThroughTheBeanDoesNot() {
        assertThat(subjects("proxy-bypass", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/recalculate")
                .doesNotContain("POST /api/insights/orders/{id}/recalculate-through-bean");
    }

    @Test
    void aRolledBackImportAnsweredWith200IsAnErrorBehindASuccess() {
        assertThat(subjects("errors-behind-2xx", null))
                .contains("POST /api/insights/orders/{id}/import")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
    }

    @Test
    void statementsWhileTheResponseIsWrittenAreLazySqlAfterTheHandler() {
        assertThat(subjects("lazy-sql-after-handler", "OBSERVED"))
                .contains("GET /api/insights/orders/report")
                .doesNotContain("GET /api/insights/orders");
    }

    @Test
    void anUnreadableBodyIsAFrameworkWarningOnItsRoute() {
        assertThat(subjects("framework-warnings-by-route", null)).contains("POST /api/insights/orders");
    }

    @Test
    void anAnonymousWriteIsReachAndAnAnonymousSuccessOnADeniedRouteIsReportedButThePublicCatalogIsNot() {
        assertThat(subjects("anonymous-data-reach", "OBSERVED"))
                .contains("POST /api/insights/debug/reset-totals")
                .doesNotContain("GET /api/sample/products", "GET /api/insights/orders");
        assertThat(subjects("anonymous-success-on-restricted-route", "OBSERVED"))
                .contains("GET /api/insights/reports/{name}")
                .doesNotContain("GET /api/sample/products");
    }

    private List<String> subjects(String kind, String status) {
        return observations.stream()
                .filter(observation -> observation.path("kind").asText().equals(kind))
                .filter(observation ->
                        status == null || observation.path("status").asText().equals(status))
                .map(observation -> observation.path("subject").asText())
                .toList();
    }
}
