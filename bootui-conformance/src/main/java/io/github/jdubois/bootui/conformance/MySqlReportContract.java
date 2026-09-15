package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Positive MySQL contract shared by live MVC, WebFlux and Quarkus consumers. */
public final class MySqlReportContract {

    private static final Set<String> SECTIONS =
            Set.of("vital-signs", "sessions", "statements", "indexes", "tables", "innodb", "replication", "settings");

    private MySqlReportContract() {}

    public static void assertRead(JsonNode report) {
        assertThat(report.path("localOnly").asBoolean()).isTrue();
        assertThat(report.path("status").asText()).isIn("READ", "PARTIAL");
        assertThat(report.path("disclaimer").asText()).isNotBlank();
        assertThat(report.path("readStartedAt").isIntegralNumber()).isTrue();
        assertThat(report.path("readAt").asLong())
                .isGreaterThanOrEqualTo(report.path("readStartedAt").asLong());
        assertThat(report.path("dataSourcesRead").asInt()).isPositive();
        assertThat(report.path("dataSources").isArray()).isTrue();
        assertThat(report.path("dataSources")).isNotEmpty();
        assertThat(report.path("diagnostics").isArray()).isTrue();
        assertThat(report.path("limitations").isArray()).isTrue();
        assertThat(report.path("truncated").isBoolean()).isTrue();
        for (JsonNode source : report.path("dataSources")) {
            if ("ERROR".equals(source.path("status").asText())) {
                continue;
            }
            assertThat(source.path("name").asText()).isNotBlank();
            Set<String> sections = StreamSupport.stream(source.path("sections").spliterator(), false)
                    .map(section -> section.path("id").asText())
                    .collect(Collectors.toSet());
            assertThat(sections).isEqualTo(SECTIONS);
            for (JsonNode section : source.path("sections")) {
                assertThat(section.path("status").asText()).isIn("AVAILABLE", "SKIPPED", "FAILED");
                assertThat(section.path("scope").asText())
                        .isIn("SERVER", "SELECTED_SCHEMA", "DEFAULT_SCHEMA_ASSOCIATED");
                assertThat(section.path("rowCount").asInt()).isNotNegative();
                assertThat(section.path("truncated").isBoolean()).isTrue();
                if (!"AVAILABLE".equals(section.path("status").asText())) {
                    assertThat(section.path("reason").asText()).isNotBlank();
                }
            }
            for (String field : List.of(
                    "capabilities",
                    "vitalSigns",
                    "sessions",
                    "lockWaits",
                    "statements",
                    "indexes",
                    "tables",
                    "innodb",
                    "replication",
                    "settings",
                    "changes")) {
                assertThat(source.path(field).isArray()).as(field).isTrue();
            }
            for (String field : List.of("vitalSigns", "innodb")) {
                for (JsonNode metric : source.path(field)) {
                    assertThat(metric.has("value")).isTrue();
                    assertThat(metric.path("value").isNull()
                                    || metric.path("value").isTextual())
                            .as("exact metric value: %s", metric.path("id"))
                            .isTrue();
                    assertThat(metric.path("unit").asText()).isNotBlank();
                    assertThat(metric.path("source").asText()).isNotBlank();
                }
            }
            for (JsonNode statement : source.path("statements")) {
                assertThat(statement.path("calls").isTextual()
                                || statement.path("calls").isNull())
                        .isTrue();
                assertThat(statement.path("digestText").isTextual()
                                || statement.path("digestText").isNull())
                        .isTrue();
            }
        }
        assertThat(report.toString())
                .doesNotContain("\"lockData\"", "\"querySampleText\"", "\"processlistInfo\"", "\"trxQuery\"");
    }

    /** Requires an available datasource and MCP enabled; exercises the same cache through all transports. */
    public static void verify(BootUiHttpProbe probe, String origin, String apiPath) {
        JsonNode panels = probe.get(apiPath + "/panels").json().path("panels");
        assertThat(StreamSupport.stream(panels.spliterator(), false)
                        .anyMatch(panel -> "mysql".equals(panel.path("id").asText())
                                && panel.path("available").asBoolean()))
                .as("the positive contract must not skip an unavailable MySQL panel")
                .isTrue();
        var initial = probe.get(apiPath + "/mysql");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("status").asText()).isEqualTo("NOT_READ");
        assertThat(initial.json().path("dataSources")).isEmpty();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Origin", origin);
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        var response = probe.post(apiPath + "/mysql/read", headers);
        assertThat(response.status()).isEqualTo(200);
        JsonNode report = response.json();
        assertRead(report);
        assertThat(probe.get(apiPath + "/mysql").json()).isEqualTo(report);
        var cli = probe.request("POST", apiPath + "/cli/tools/get_mysql_report", headers, "{}");
        assertThat(cli.status()).isEqualTo(200);
        assertThat(cli.json()).isEqualTo(report);
        assertThat(mcp(probe, apiPath, headers, "get_mysql_report")).isEqualTo(report);

        var cliRead = probe.request("POST", apiPath + "/cli/tools/mysql_read", headers, "{}");
        assertThat(cliRead.status()).isEqualTo(200);
        assertRead(cliRead.json());
        assertThat(probe.get(apiPath + "/mysql").json()).isEqualTo(cliRead.json());
        JsonNode mcpRead = mcp(probe, apiPath, headers, "mysql_read");
        assertRead(mcpRead);
        assertThat(probe.get(apiPath + "/mysql").json()).isEqualTo(mcpRead);

        var rejected = probe.post(
                apiPath + "/mysql/read", Map.of("Origin", "https://foreign.invalid", "Sec-Fetch-Site", "cross-site"));
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.json().path("error").asText()).isNotBlank();
        assertThat(probe.get(apiPath + "/mysql").json()).isEqualTo(mcpRead);
    }

    private static JsonNode mcp(BootUiHttpProbe probe, String apiPath, Map<String, String> headers, String tool) {
        var response = probe.request(
                "POST",
                apiPath + "/mcp",
                headers,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":{}}}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json().path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").isArray()).isTrue();
        try {
            return new ObjectMapper()
                    .readTree(result.path("content").get(0).path("text").asText());
        } catch (IOException ex) {
            throw new AssertionError("MySQL MCP report must be valid JSON", ex);
        }
    }
}
