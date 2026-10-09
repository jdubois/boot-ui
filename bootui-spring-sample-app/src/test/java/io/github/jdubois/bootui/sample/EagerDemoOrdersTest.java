package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.sample.insights.EagerDemoOrderSeed;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_eager_demo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/eager-demo/application-bootui.properties"
        })
class EagerDemoOrdersTest {

    @LocalServerPort
    int port;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    HibernateScanner hibernateScanner;

    @Autowired
    RuntimeJournal journal;

    @Test
    void eagerLoadingProducesCorrelatedNPlusOneAndTheJoinedControlDoesNot() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var slow = probe.get("/api/insights/eager-orders");
        assertThat(slow.status()).isEqualTo(200);
        assertThat(slow.json().size()).isEqualTo(EagerDemoOrderSeed.ORDER_COUNT);
        long slowStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        var joined = probe.get("/api/insights/eager-orders/joined");
        assertThat(joined.status()).isEqualTo(200);
        assertThat(joined.json()).isEqualTo(slow.json());
        long joinedStatements = statistics.getPrepareStatementCount();

        assertThat(slowStatements).isGreaterThanOrEqualTo(EagerDemoOrderSeed.ORDER_COUNT + 1);
        assertThat(joinedStatements).isLessThanOrEqualTo(2);

        JsonNode activity = probe.get("/bootui/api/activity?source=journal&type=REQUEST&limit=200")
                .json();
        List<JsonNode> slowRequests = new ArrayList<>();
        List<JsonNode> joinedRequests = new ArrayList<>();
        activity.path("entries").forEach(entry -> {
            if ("/api/insights/eager-orders".equals(entry.path("path").asText())) {
                slowRequests.add(entry);
            }
            if ("/api/insights/eager-orders/joined".equals(entry.path("path").asText())) {
                joinedRequests.add(entry);
            }
        });
        assertThat(slowRequests)
                .anySatisfy(
                        entry -> assertThat(entry.path("sqlNPlusOneSuspected").asBoolean())
                                .isTrue());
        assertThat(joinedRequests)
                .allSatisfy(
                        entry -> assertThat(entry.path("sqlNPlusOneSuspected").asBoolean())
                                .isFalse());

        for (int i = 0; i < 2; i++) {
            assertThat(probe.get("/api/insights/eager-orders").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/eager-orders/joined").status()).isEqualTo(200);
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
        List<JsonNode> observations = new ArrayList<>();
        probe.get("/bootui/api/runtime-insights").json().path("observations").forEach(observations::add);
        assertThat(observations.stream()
                        .filter(item ->
                                "repeated-selects".equals(item.path("kind").asText()))
                        .filter(item -> "OBSERVED".equals(item.path("status").asText()))
                        .map(item -> item.path("subject").asText()))
                .contains("GET /api/insights/eager-orders")
                .doesNotContain("GET /api/insights/eager-orders/joined");

        HibernateReport scan = hibernateScanner.scan();
        assertThat(scan.results()).extracting(result -> result.id()).contains("HIB-FETCH-001", "HIB-QUERY-005");
        assertThat(scan.results())
                .filteredOn(result -> "HIB-FETCH-001".equals(result.id()))
                .singleElement()
                .satisfies(result -> assertThat(result.sampleViolations())
                        .anySatisfy(sample -> assertThat(sample).contains("EagerDemoOrder#customer")));
        assertThat(scan.results())
                .filteredOn(result -> "HIB-QUERY-005".equals(result.id()))
                .singleElement()
                .satisfies(result -> assertThat(result.sampleViolations())
                        .anySatisfy(sample -> assertThat(sample).contains("findWithSecondarySelects")));
    }
}
