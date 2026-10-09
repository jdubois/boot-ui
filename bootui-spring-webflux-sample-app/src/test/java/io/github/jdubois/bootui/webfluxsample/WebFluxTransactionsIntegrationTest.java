package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The Transactions panel captures the reactive sample's {@code @Transactional} boundaries, which run on a Reactor
 * {@code boundedElastic} worker over the blocking JDBC datasource: a commit, a slow commit, and a rollback, through the
 * panel's endpoint and the {@code get_transactions} MCP tool alike. It fails if capture regresses on WebFlux.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/transactions-it/application-bootui.properties",
            "bootui.claude-code.enabled=OFF",
            "bootui.mcp.enabled=ON"
        })
class WebFluxTransactionsIntegrationTest {

    private static final String SCENARIOS = "SampleTransactionScenarios.";

    @LocalServerPort
    int port;

    @Test
    void reactiveSamplesTransactionsAreCapturedByThePanelAndMcp() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        BootUiHttpProbe.Response samples = probe.get("/api/sample/transaction-samples");
        assertThat(samples.status()).as(samples.body()).isEqualTo(200);
        assertThat(samples.json().path("scenarios")).hasSize(3);

        JsonNode report = probe.get("/bootui/api/transactions").json();
        assertThat(report.path("available").asBoolean()).as(report.toString()).isTrue();
        assertSamplesCaptured(report);

        BootUiHttpProbe.Response call = probe.request(
                "POST",
                "/bootui/api/mcp",
                Map.of("Content-Type", "application/json"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_transactions\"}}");
        JsonNode result = call.json().path("result");
        assertThat(result.path("isError").asBoolean()).as(call.body()).isFalse();
        assertSamplesCaptured(new ObjectMapper()
                .readTree(result.path("content").get(0).path("text").asText()));
    }

    private static void assertSamplesCaptured(JsonNode report) {
        List<JsonNode> samples = new ArrayList<>();
        report.path("entries").forEach(entry -> {
            if (entry.path("methodName").asText().contains(SCENARIOS)) {
                samples.add(entry);
            }
        });
        assertThat(samples)
                .as(report.toString())
                .anySatisfy(entry -> {
                    assertThat(entry.path("methodName").asText()).endsWith(SCENARIOS + "commit");
                    assertThat(entry.path("status").asText()).isEqualTo("COMMITTED");
                    assertThat(entry.path("readOnly").asBoolean()).isTrue();
                    assertThat(entry.path("thread").asText()).containsIgnoringCase("boundedElastic");
                })
                .anySatisfy(entry -> {
                    assertThat(entry.path("methodName").asText()).endsWith(SCENARIOS + "slowCommit");
                    assertThat(entry.path("status").asText()).isEqualTo("COMMITTED");
                    assertThat(entry.path("slow").asBoolean()).isTrue();
                })
                .anySatisfy(entry -> {
                    assertThat(entry.path("methodName").asText()).endsWith(SCENARIOS + "rollBack");
                    assertThat(entry.path("status").asText()).isEqualTo("ROLLED_BACK");
                });
    }
}
