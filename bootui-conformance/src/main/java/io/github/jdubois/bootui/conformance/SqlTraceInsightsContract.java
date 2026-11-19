package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.ArrayList;
import java.util.List;

/** Bounded SQL insights wire contract, reusable by capability-specific integration fixtures. */
public final class SqlTraceInsightsContract {

    private SqlTraceInsightsContract() {}

    public static JsonNode read(BootUiHttpProbe probe, String apiPath) {
        Response response = probe.get(apiPath + "/sql-trace/insights");
        assertThat(response.status()).as("GET /sql-trace/insights status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /sql-trace/insights content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").isBoolean()).as("insights.available").isTrue();
        assertThat(root.path("capturing").isBoolean()).as("insights.capturing").isTrue();
        assertThat(root.path("notes").isArray()).as("insights.notes").isTrue();
        return root;
    }

    public static void sqlTraceInsightsAreBoundedAndDiscloseTheirCorrelationTiers(JsonNode root) {
        JsonNode window = root.path("window");
        assertThat(window.isObject()).as("insights.window").isTrue();
        for (String field :
                List.of("retainedStatements", "bufferSize", "evicted", "totalCaptured", "totalDurationMillis")) {
            assertThat(window.path(field).isNumber())
                    .as("insights.window.%s must be numeric", field)
                    .isTrue();
        }

        int topPerCriterion = root.path("topPerCriterion").asInt(-1);
        assertThat(topPerCriterion).as("insights.topPerCriterion").isPositive();
        JsonNode statements = root.path("statements");
        assertThat(statements.isArray()).as("insights.statements").isTrue();
        assertThat(statements.size())
                .as("ranked statements must stay bounded by the seven ranking criteria")
                .isLessThanOrEqualTo(7 * topPerCriterion);
        for (JsonNode statement : statements) {
            for (String field : List.of(
                    "executions",
                    "totalDurationMillis",
                    "maxDurationMillis",
                    "avgDurationMillis",
                    "errorCount",
                    "p50DurationMillis",
                    "p95DurationMillis",
                    "p99DurationMillis",
                    "shareOfRetainedTimePercent")) {
                assertThat(statement.path(field).isNumber())
                        .as("ranked statement field '%s'", field)
                        .isTrue();
            }
            assertThat(statement.path("sql").isTextual())
                    .as("ranked statement sql")
                    .isTrue();
            assertThat(statement.path("entryIds").isArray())
                    .as("ranked statement must deep-link to retained executions")
                    .isTrue();
            assertThat(statement.path("entryIdsTruncated").isBoolean())
                    .as("a ranked statement must say when its deep link covers only part of the group")
                    .isTrue();
            JsonNode topFor = statement.path("topFor");
            assertThat(topFor.isArray())
                    .as("a ranked statement must say which criteria earned it its place")
                    .isTrue();
            assertThat(topFor.size())
                    .as("a ranked statement cannot lead more criteria than exist")
                    .isBetween(1, 7);
            topFor.forEach(criterion -> assertThat(criterion.asText())
                    .as("ranking criterion")
                    .isIn(
                            "TOTAL_DURATION",
                            "MAX_DURATION",
                            "EXECUTIONS",
                            "AVG_DURATION",
                            "ERROR_COUNT",
                            "P95_DURATION",
                            "P99_DURATION"));
        }

        JsonNode attribution = root.path("attribution");
        assertThat(attribution.path("available").isBoolean())
                .as("attribution.available")
                .isTrue();
        List<String> tiers = new ArrayList<>();
        attribution.path("supportedCorrelations").forEach(tier -> tiers.add(tier.asText()));
        assertThat(tiers)
                .as("every runtime must offer trace-id correlation and disclose the tiers it uses")
                .contains("TRACE_ID");
        assertThat(tiers).isSubsetOf("TRACE_ID", "SERVING_THREAD", "TIME_WINDOW");
        for (String bucket : List.of("unattributed", "ambiguous")) {
            JsonNode node = attribution.path(bucket);
            assertThat(node.path("executions").isNumber())
                    .as("attribution.%s.executions", bucket)
                    .isTrue();
            assertThat(node.path("reason").isTextual())
                    .as("attribution.%s must explain itself rather than showing a bare number", bucket)
                    .isTrue();
        }

        JsonNode routes = attribution.path("routes");
        assertThat(routes.isArray()).as("attribution.routes").isTrue();
        assertThat(routes.size()).as("route ranking must stay bounded").isLessThanOrEqualTo(20);
        for (JsonNode route : routes) {
            assertThat(route.path("routeSource").asText())
                    .as("route grouping key must declare its provenance")
                    .isIn("ROUTE_TEMPLATE", "MASKED_PATH");
            assertThat(route.path("route").asText())
                    .as("a route grouping key must never carry a query string")
                    .doesNotContain("?");
            assertThat(route.path("topStatements").isArray())
                    .as("route.topStatements")
                    .isTrue();
            assertThat(route.path("topStatements").size())
                    .as("route-by-statement cross product must stay bounded")
                    .isLessThanOrEqualTo(5);
        }
    }
}
