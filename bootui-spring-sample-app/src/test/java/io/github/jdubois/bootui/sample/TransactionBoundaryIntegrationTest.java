package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.autoconfigure.transactions.BootUiTransactionExecutionListener;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.sample.insights.InsightAuditWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_transaction_boundaries;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.mcp.enabled=ON",
            "bootui.overrides-file=target/transaction-boundaries/application-bootui.properties"
        })
class TransactionBoundaryIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TransactionRecorder recorder;

    @Autowired
    RuntimeJournal journal;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InsightAuditWriter audit;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    @Autowired
    BootUiTransactionExecutionListener listener;

    @Test
    void confirmAndShipExposeTheirActualPhysicalBoundariesAcrossTransports() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        assertThat(probe.post("/api/insights/orders/2/confirm", Map.of()).status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/3/ship", Map.of()).status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        JsonNode rest = probe.get("/bootui/api/transactions").json();
        JsonNode confirm = entry(rest, "InsightOrderService.confirm");
        JsonNode child = entry(rest, "InsightAuditWriter.recordInItsOwnTransaction");
        JsonNode ship = entry(rest, "InsightOrderService.ship");
        assertThat(confirm.path("propagation").asText()).isEqualTo("NEW");
        assertThat(child.path("propagation").asText()).isEqualTo("REQUIRES_NEW");
        assertThat(child.path("parentId").asLong()).isEqualTo(confirm.path("id").asLong());
        // SQL correlation uses millisecond windows, so the parent statement can share this boundary's start tick.
        assertThat(child.path("connectionCount").asInt()).isBetween(1, 2);
        assertThat(confirm.path("connectionCount").asInt()).isEqualTo(2);
        assertThat(ship.path("connectionCount").asInt()).isEqualTo(1);
        assertThat(ship.path("propagation").asText()).isEqualTo("NEW");

        List<RuntimeEvent> confirmEvents = requestEvents("/api/insights/orders/2/confirm");
        assertThat(confirmEvents)
                .filteredOn(event -> event.payload() instanceof ConnectionPayload)
                .hasSize(2);
        assertThat(confirmEvents)
                .filteredOn(event -> event.payload() instanceof TransactionPayload)
                .extracting(event -> ((TransactionPayload) event.payload()).propagation())
                .containsExactlyInAnyOrder("REQUIRED", "REQUIRES_NEW");
        assertThat(confirmEvents)
                .filteredOn(event -> event.payload() instanceof TransactionPayload)
                .allSatisfy(event -> assertThat(event.requestId())
                        .isEqualTo(confirmEvents.get(0).requestId()));
        List<RuntimeEvent> shipEvents = requestEvents("/api/insights/orders/3/ship");
        assertThat(shipEvents)
                .filteredOn(event -> event.payload() instanceof ConnectionPayload)
                .hasSize(1);
        assertThat(shipEvents)
                .filteredOn(event -> event.payload() instanceof TransactionPayload)
                .hasSize(1);

        var cli = probe.request(
                "POST", "/bootui/api/cli/tools/get_transactions", Map.of("Content-Type", "application/json"), "{}");
        assertThat(cli.status()).isEqualTo(200);
        assertThat(entry(cli.json(), "InsightAuditWriter.recordInItsOwnTransaction"))
                .isEqualTo(child);
        var mcp = probe.request(
                "POST",
                "/bootui/api/mcp",
                Map.of("Content-Type", "application/json"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_transactions\"}}");
        assertThat(mcp.status()).isEqualTo(200);
        JsonNode mcpReport = new ObjectMapper()
                .readTree(mcp.json()
                        .path("result")
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText());
        assertThat(entry(mcpReport, "InsightAuditWriter.recordInItsOwnTransaction"))
                .isEqualTo(child);
    }

    @Test
    void theExistingAuditBeanCommitsEvenWhenItsOuterTransactionRollsBack() {
        String action = UUID.randomUUID().toString();
        Long before = jdbc.queryForObject("select total_cents from insight_orders where id = 2", Long.class);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setName("BoundaryTest.outerRollback");
        outer.executeWithoutResult(status -> {
            jdbc.update("update insight_orders set total_cents = -1595 where id = 2");
            audit.recordInItsOwnTransaction(2, action);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select total_cents from insight_orders where id = 2", Long.class))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from insight_audit where action = ?", Long.class, action))
                .isEqualTo(1);
        TransactionEntryDto parent = recorder.recent().stream()
                .filter(entry -> entry.methodName().equals("BoundaryTest.outerRollback"))
                .findFirst()
                .orElseThrow();
        assertThat(parent.status()).isEqualTo("ROLLED_BACK");
        assertThat(recorder.recent())
                .filteredOn(entry -> Long.valueOf(parent.id()).equals(entry.parentId()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.propagation()).isEqualTo("REQUIRES_NEW");
                    assertThat(entry.status()).isEqualTo("COMMITTED");
                });
    }

    @Test
    void realRequiredParticipationAndSavepointRollbackDoNotBecomeIndependentTransactions() {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setTransactionExecutionListeners(List.of(listener));
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setName("BoundaryTest.jdbcOuter");
        TransactionTemplate participant = new TransactionTemplate(manager);
        participant.setName("BoundaryTest.required");
        TransactionTemplate nested = new TransactionTemplate(manager);
        nested.setName("BoundaryTest.savepoint");
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        String action = UUID.randomUUID().toString();
        outer.executeWithoutResult(status -> {
            participant.executeWithoutResult(joined -> jdbc.queryForObject("select 1", Integer.class));
            nested.executeWithoutResult(savepoint -> {
                audit.record(2, action);
                savepoint.setRollbackOnly();
            });
        });
        assertThat(jdbc.queryForObject("select count(*) from insight_audit where action = ?", Long.class, action))
                .isZero();
        assertThat(recorder.recent()).noneMatch(entry -> entry.methodName().equals("BoundaryTest.required"));
        TransactionEntryDto parent = recorder.recent().stream()
                .filter(entry -> entry.methodName().equals("BoundaryTest.jdbcOuter"))
                .findFirst()
                .orElseThrow();
        assertThat(recorder.recent())
                .filteredOn(entry -> entry.methodName().equals("BoundaryTest.savepoint"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.propagation()).isEqualTo("NESTED");
                    assertThat(entry.status()).isEqualTo("ROLLED_BACK");
                    assertThat(entry.parentId()).isEqualTo(parent.id());
                });
    }

    private List<RuntimeEvent> requestEvents(String path) {
        String request = journal.entries().stream()
                .map(entry -> entry.event())
                .filter(event -> event.payload() instanceof HttpPayload http && path.equals(http.path()))
                .map(RuntimeEvent::requestId)
                .findFirst()
                .orElseThrow();
        return journal.entries().stream()
                .map(entry -> entry.event())
                .filter(event -> request.equals(event.requestId()))
                .toList();
    }

    private static JsonNode entry(JsonNode report, String suffix) {
        for (JsonNode entry : report.path("entries")) {
            if (entry.path("methodName").asText().endsWith(suffix)) {
                return entry;
            }
        }
        throw new AssertionError("Missing transaction " + suffix + " in " + report);
    }
}
