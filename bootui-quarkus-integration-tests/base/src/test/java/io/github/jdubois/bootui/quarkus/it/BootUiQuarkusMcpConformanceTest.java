package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusMcpConformanceTest.ConformanceProfile.class)
class BootUiQuarkusMcpConformanceTest extends AbstractMcpConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static class ConformanceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.overrides-file", "target/mcp-conformance/application-bootui.properties",
                    "bootui.panels.copilot.enabled", "false",
                    "bootui.panels.heap-dump.read-only", "true",
                    "bootui.heap-dump.capture-enabled", "false",
                    "bootui.claude-code.enabled", "OFF",
                    "bootui.mcp.max-payload-bytes", "256");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Override
    protected String baseUrl() {
        return baseUrl.toExternalForm();
    }

    @Override
    protected String requestProfileProbePath() {
        return "/it/correlation/worker";
    }

    @Test
    void quarkusSpringNamedAdvisorKeepsCompactAndRuleCatalogContracts() throws Exception {
        assertThat(enableMcp()).isTrue();
        try {
            JsonNode scanEnvelope = callTool("spring_scan", Map.of());
            assertThat(scanEnvelope.path("isError").asBoolean()).isFalse();
            JsonNode summary = JSON.readTree(
                    scanEnvelope.path("content").get(0).path("text").asText());
            assertThat(summary.path("results").isMissingNode()).isTrue();
            assertThat(summary.path("reportTool").asText()).isEqualTo("get_spring_report");
            String scanId = summary.path("violationDetails").path("scanId").asText();
            assertThat(scanId).isNotBlank();

            JsonNode reportEnvelope = callTool("get_spring_report", Map.of());
            assertThat(reportEnvelope.path("isError").asBoolean()).isFalse();
            JsonNode report = JSON.readTree(
                    reportEnvelope.path("content").get(0).path("text").asText());
            assertThat(report.path("violationDetails").path("scanId").asText()).isEqualTo(scanId);
            Set<String> results = ids(report.path("results"));
            Set<String> errors = ids(report.path("analysisErrors"));
            assertThat(results)
                    .as("a Quarkus rule has one outcome in the full cached report")
                    .allSatisfy(id -> assertThat(errors).doesNotContain(id));

            JsonNode knownWithoutFindings =
                    callTool("get_spring_rule_violations", Map.of("id", "QA-PERF-002", "scanId", scanId));
            assertThat(knownWithoutFindings.path("isError").asBoolean()).isTrue();
            assertThat(knownWithoutFindings.path("content").get(0).path("text").asText())
                    .isEqualTo(io.github.jdubois.bootui.engine.advisor.AdvisorScanState.NO_FINDINGS_MESSAGE);

            JsonNode unknown =
                    callTool("get_spring_rule_violations", Map.of("id", "definitely-unknown", "scanId", scanId));
            assertThat(unknown.path("isError").asBoolean()).isTrue();
            assertThat(unknown.path("content").get(0).path("text").asText())
                    .isEqualTo(io.github.jdubois.bootui.engine.advisor.AdvisorScanState.UNKNOWN_RULE_MESSAGE);
        } finally {
            disableMcp();
        }
    }

    private JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "tools/call",
                "params",
                Map.of("name", name, "arguments", arguments)));
        Response response = new BootUiHttpProbe(baseUrl.toExternalForm())
                .request("POST", "/bootui/api/mcp", Map.of("Content-Type", "application/json"), body);
        assertThat(response.status()).isEqualTo(200);
        return response.json().path("result");
    }

    private static Set<String> ids(JsonNode entries) {
        return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(entry -> entry.path("id").asText())
                .collect(Collectors.toSet());
    }
}
