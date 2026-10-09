package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shared black-box HTTP conformance contract for the BootUI command-line endpoint at
 * {@code /bootui/api/cli}.
 *
 * <p>The whole point of this endpoint is that a {@code bootui} CLI built once works against any BootUI
 * instance, whichever stack it runs on. These tests are what makes that true: they pin the catalog shape,
 * the argument handling, and — most importantly — the HTTP status each refusal produces, since a shell
 * branches on a status code rather than on a message.
 *
 * <p>Every case here goes through the same {@code McpDispatcher} logic MCP uses, so a divergence in panel
 * policy between the two surfaces would show up as a failure here.
 */
public abstract class AbstractCliConformanceTest {

    private static final String CLI = "/bootui/api/cli";

    /**
     * A tool such as {@code architecture_scan} runs a whole scan inside its request, which a loaded runner can stretch
     * past the default 30 s. Implementations raise the server's {@code bootui.cli.execution-timeout} to 60 s, the
     * {@code bootui} CLI's own request budget; the probe waits a little longer, so a true overrun reads as the server's
     * 504 rather than a client timeout.
     */
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(70);

    protected abstract String baseUrl();

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    private Response invoke(String tool, String body) {
        return probe().request(
                        "POST", CLI + "/tools/" + tool, Map.of("Content-Type", "application/json"), body, CLI_TIMEOUT);
    }

    private JsonNode catalogEntry(String tool) {
        for (JsonNode entry : probe().get(CLI).json().path("tools")) {
            if (tool.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Tool " + tool + " is not advertised by this instance");
    }

    @Test
    void testPentestingToolsApplyLiveDismissalsToScanAndCachedRead() {
        PentestingDismissalContract.verify(
                probe(),
                "/bootui/api",
                Path.of("target/cli-conformance/boot-ui.yml"),
                () -> PentestingDismissalContract.response(invoke("pentest_scan", "{}")),
                () -> PentestingDismissalContract.response(invoke("get_pentest_report", "{}")));
    }

    @Test
    void testCliCatalogDescribesTheToolsThisInstanceExposes() {
        Response response = probe().get(CLI);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode body = response.json();
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("serverName").asText()).isEqualTo("bootui");
        assertThat(body.path("endpoint").asText()).isEqualTo(CLI);
        assertThat(body.path("maxResults").asInt()).isPositive();
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools")).isNotEmpty();
        assertThat(body.path("toolCount").asInt()).isEqualTo(body.path("tools").size());

        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isNotBlank();
        assertThat(tool.path("description").asText()).isNotBlank();
        assertThat(tool.path("panel").asText()).isNotBlank();
        assertThat(tool.path("schema").asText()).isIn("NONE", "LIMIT", "QUERY_LIMIT", "QUERY", "ID", "RULE_VIOLATIONS");
        assertThat(tool.path("arguments").isArray()).isTrue();
        assertThat(tool.path("action").isBoolean()).isTrue();
        assertThat(tool.path("panelEnabled").isBoolean()).isTrue();
        assertThat(tool.path("panelReadOnly").isBoolean()).isTrue();
    }

    @Test
    void testCliAdvisorViolationPagesUseTheScanSnapshotAndStrictArguments() {
        JsonNode entry = catalogEntry("get_architecture_rule_violations");
        assertThat(entry.path("schema").asText()).isEqualTo("RULE_VIOLATIONS");
        assertThat(entry.path("command").asText()).contains("architecture violations");
        Response scan = invoke("architecture_scan", "{}");
        assertThat(scan.status()).isEqualTo(200);
        String scanId = scan.json().path("violationDetails").path("scanId").asText();
        assertThat(scanId).isNotBlank();
        assertThat(scan.json().has("results")).isFalse();
        if (!scan.json().path("topFindings").isEmpty()) {
            JsonNode rule = scan.json().path("topFindings").get(0);
            Response page = invoke(
                    "get_architecture_rule_violations",
                    "{\"id\":\"" + rule.path("id").asText() + "\",\"scanId\":\"" + scanId
                            + "\",\"offset\":0,\"limit\":1}");
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.json().path("scanId").asText()).isEqualTo(scanId);
            assertThat(page.json().path("violationCount").asInt())
                    .isEqualTo(rule.path("count").asInt());
            assertThat(page.json().path("page").path("limit").asInt()).isEqualTo(1);
        }
        assertThat(invoke("get_architecture_rule_violations", "{\"id\":\"ARCH-CODE-002\"}")
                        .status())
                .isEqualTo(400);
        assertThat(invoke(
                                "get_architecture_rule_violations",
                                "{\"id\":\"ARCH-CODE-002\",\"scanId\":\"" + scanId + "\",\"offset\":-1}")
                        .status())
                .isEqualTo(400);
        assertThat(invoke(
                                "get_architecture_rule_violations",
                                "{\"id\":\"ARCH-CODE-002\",\"scanId\":\"stale-snapshot\"}")
                        .status())
                .isEqualTo(409);
        assertThat(invoke("get_architecture_report", "{}")
                        .json()
                        .path("violationDetails")
                        .path("scanId")
                        .asText())
                .isEqualTo(scanId);
    }

    @Test
    void testCliCatalogIsServedWithoutEnablingTheMcpServer() {
        // The MCP server is off in every conformance profile. If the catalog still answers, the CLI does not
        // inherit the MCP toggle as a prerequisite — the reason this endpoint exists at all.
        Response mcp = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertThat(mcp.json().path("error").path("code").asInt()).isEqualTo(-32000);

        assertThat(probe().get(CLI).status()).isEqualTo(200);
        assertThat(invoke("get_overview", "{}").status()).isEqualTo(200);
    }

    @Test
    void testCliReadToolReturnsThePayloadWithoutAJsonRpcEnvelope() {
        Response response = invoke("get_overview", "{}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        assertThat(response.json().isObject()).isTrue();
        assertThat(response.json().has("jsonrpc")).isFalse();
        assertThat(response.json().has("result")).isFalse();
    }

    @Test
    void testCliLogTailToolFollowsTheExposurePolicy() {
        LogTailExposureContract contract = new LogTailExposureContract().log();

        contract.assertMaskedIn(cliLogTail(), "bootui logs tail");
        LogTailExposureContract.withExposure(
                "METADATA_ONLY",
                null,
                () -> contract.assertOmittedIn(cliLogTail(), "bootui logs tail (METADATA_ONLY)"));
        LogTailExposureContract.withExposure(
                "FULL", null, () -> contract.assertVerbatimIn(cliLogTail(), "bootui logs tail (FULL)"));
    }

    private JsonNode cliLogTail() {
        Response response = invoke("get_log_tail", "{}");
        assertThat(response.status()).isEqualTo(200);
        return response.json().path("entries");
    }

    @Test
    void testCliReadToolAcceptsAnEmptyBody() {
        Response response = probe().request("POST", CLI + "/tools/get_overview", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void testCliSearchToolAcceptsQueryAndLimit() {
        Response response = invoke("get_config", "{\"query\":\"spring\",\"limit\":5}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
    }

    @Test
    void testCliUnknownToolIsNotFound() {
        Response response = invoke("no_such_tool", "{}");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().path("error").asText()).contains("no_such_tool");
    }

    /**
     * The agent's commands without the BootUI agent ({@code docs/PLAN-v2.md} M5-10's acceptance pass): the catalog
     * lists {@code bootui agent status}, which answers that the agent is not attached and why, and none of the commands
     * that need one of its sensors, which answer 404 as a command this instance does not serve, never an empty success.
     */
    @Test
    void testCliAgentCommandsSayTheAgentIsNotAttachedAndTheOthersAreNotServed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                JavaAgentPresence.detached(), "this JVM runs with the BootUI agent attached");
        java.util.List<String> listed = new java.util.ArrayList<>();
        probe().get(CLI)
                .json()
                .path("tools")
                .forEach(tool -> listed.add(tool.path("name").asText()));
        assertThat(listed).contains("get_agent_status");
        assertThat(listed).doesNotContainAnyElementsOf(JavaAgentPresence.AGENT_SENSOR_TOOLS);

        Response status = invoke("get_agent_status", "{}");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().path("state").asText()).isEqualTo("NOT_ATTACHED");
        assertThat(status.json().path("reason").asText()).isNotBlank();

        for (String tool : JavaAgentPresence.AGENT_SENSOR_TOOLS) {
            Response response = invoke(tool, "{}");
            assertThat(response.status()).as("%s without the agent", tool).isEqualTo(404);
            assertThat(response.json().path("error").asText()).contains(tool);
        }
    }

    @Test
    void testCliArgumentTheToolDoesNotDeclareIsRejected() {
        Response response = invoke("get_overview", "{\"id\":\"anything\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliPropertyOutsideEveryToolSchemaIsRejectedRatherThanIgnored() {
        // A misspelled filter must not come back as a successful, unfiltered report: the body reaches the
        // dispatcher verbatim, so binding cannot quietly drop what the tool never declared.
        Response response = invoke("get_config", "{\"q\":\"spring\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).contains("q");
    }

    @Test
    void testCliArgumentOfTheWrongTypeIsRejected() {
        Response response = invoke("get_config", "{\"limit\":\"ten\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliRequestProfileRefusesAnIdNeitherRetentionWindowHas() {
        String id = "conformance-unknown-request";
        assertThat(catalogEntry("get_request_profile").path("panel").asText()).isEqualTo("activity");

        Response cli = invoke("get_request_profile", "{\"id\":\"" + id + "\"}");
        assertThat(cli.status())
                .as("an unknown id is the caller's mistake, so the CLI exits with an error")
                .isEqualTo(400);
        assertThat(cli.json().path("error").asText()).contains(id, "journal", "buffer", "get_live_activity");
    }

    @Test
    void testCliRuntimeInsightsCommandsAnswerOnEveryStack() {
        assertThat(catalogEntry("get_runtime_insights").path("command").asText())
                .isEqualTo("insights list");
        Response list = invoke("get_runtime_insights", "{\"query\":\"\",\"limit\":2}");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json().has("coverage")).isTrue();
        assertThat(list.json().path("observations").size()).isLessThanOrEqualTo(2);
        assertThat(invoke("get_runtime_insights", "{}")
                        .json()
                        .path("observations")
                        .size())
                .as("bootui insights list without --limit gets the compact default")
                .isLessThanOrEqualTo(8);
        for (JsonNode observation : invoke("get_runtime_insights", "{}").json().path("observations")) {
            assertThat(observation.path("listed").asBoolean(false))
                    .as("bootui insights list shows only rows listed by default (M4-19)")
                    .isTrue();
        }
        assertThat(invoke("get_runtime_insights", "{\"query\":\"all\"}").status())
                .isEqualTo(200);
        Response comparison = invoke("get_runtime_run_comparison", "{\"id\":\"previous\"}");
        assertThat(comparison.status()).isEqualTo(200);
        assertThat(comparison.json().path("status").asText()).isNotBlank();
        assertThat(invoke("get_runtime_insight", "{}").status()).isEqualTo(400);
        assertThat(invoke("get_runtime_insight", "{}").json().path("error").asText())
                .as("a missing id names the command that lists the ids")
                .contains("get_runtime_insights");
        Response unknown = invoke("get_runtime_insight", "{\"id\":\"conformance-unknown-observation\"}");
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().path("error").asText())
                .contains("conformance-unknown-observation", "get_runtime_insights");
    }

    @Test
    void testCliRequestProfileRequiresAnId() {
        assertThat(invoke("get_request_profile", "{}").status()).isEqualTo(400);
    }

    @Test
    void testCliMissingRequiredIdIsRejected() {
        Response response = invoke("get_exception_detail", "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliAgentSensorSwitchesRejectMissingAndUnsupportedIds() {
        for (String tool : java.util.List.of("enable_agent_sensor", "disable_agent_sensor")) {
            JsonNode entry = catalogEntry(tool);
            assertThat(entry.path("panel").asText()).isEqualTo("java-agent");
            assertThat(entry.path("action").asBoolean()).isTrue();
            assertThat(entry.path("command").asText())
                    .isEqualTo(tool.equals("enable_agent_sensor") ? "agent sensor enable" : "agent sensor disable");
            assertThat(invoke(tool, "{}").status()).isEqualTo(400);
            Response invalid = invoke(tool, "{\"id\":\"executors\"}");
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.json().path("error").asText()).contains("cannot be switched", "executors");
            Response refused = invoke(tool, "{\"id\":\"files\"}");
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.json().path("error").asText()).contains("not armed");
        }
    }

    @Test
    void testCliDisabledPanelRefusesItsTools() {
        // Every conformance profile disables the Memory panel. It is deliberately a panel every stack
        // reports as available on any machine: a panel that is *unavailable* drops its tools from the
        // registry entirely, so the refusal under test would be indistinguishable from an unknown tool.
        assertThat(catalogEntry("get_memory_report").path("panelEnabled").asBoolean())
                .as("the profile must disable a panel this instance actually advertises")
                .isFalse();

        Response response = invoke("get_memory_report", "{}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliReadOnlyPanelRefusesActionsButStillServesReads() {
        // Every conformance profile marks the Heap Dump panel read-only.
        assertThat(invoke("analyze_heap_dump", "{}").status()).isEqualTo(403);
        assertThat(invoke("get_heap_dump_report", "{}").status()).isEqualTo(200);
    }

    @Test
    void testCliGetRejectsToolInvocation() {
        Response response = probe().get(CLI + "/tools/get_overview");

        assertThat(response.status()).isIn(404, 405);
    }
}
