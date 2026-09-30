package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus capture layer: a request through the host app's own loopback port is sampled into
 * the shared engine ring buffer by the Vert.x capture filter and surfaced by both
 * {@code GET /bootui/api/http-exchanges} and {@code GET /bootui/api/activity}. Also pins that
 * sensitive headers are masked, BootUI's own traffic is self-excluded, and Live Activity merges the
 * exceptions source while honestly degrading SQL trace when no datasource is configured.
 */
@QuarkusTest
class BootUiQuarkusHttpExchangesCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void httpExchangesCaptureHostRequestAndMaskAuthorization() {
        BootUiHttpProbe probe = probe();
        probe.get("/api/hello", Map.of("Authorization", "Bearer supersecret"));

        Response response = probe.get("/bootui/api/http-exchanges");
        assertThat(response.status()).as("http-exchanges status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();

        JsonNode report = response.json();
        assertThat(report.path("recorded").asInt())
                .as("at least one exchange recorded")
                .isGreaterThan(0);

        boolean foundHello = false;
        boolean foundSelf = false;
        for (JsonNode ex : report.path("exchanges")) {
            String path = ex.path("path").asText("");
            if (path.equals("/api/hello")) {
                foundHello = true;
                for (JsonNode header : ex.path("requestHeaders")) {
                    if (header.path("name").asText().equalsIgnoreCase("authorization")) {
                        assertThat(header.path("masked").asBoolean())
                                .as("authorization masked")
                                .isTrue();
                        assertThat(header.path("values").path(0).asText()).isEqualTo("******");
                    }
                }
            }
            if (path.startsWith("/bootui")) {
                foundSelf = true;
            }
        }
        assertThat(foundHello).as("captured host request to /api/hello").isTrue();
        assertThat(foundSelf).as("BootUI's own traffic is self-excluded").isFalse();
    }

    @Test
    void liveActivityMergesRequestsAndDegradesSqlCleanly() {
        BootUiHttpProbe probe = probe();
        probe.get("/api/hello");

        Response response = probe.get("/bootui/api/activity");
        assertThat(response.status()).as("activity status").isEqualTo(200);
        JsonNode report = response.json();
        assertThat(report.path("available").asBoolean()).isTrue();
        assertThat(report.path("typeCounts").path("REQUEST").asInt()).isGreaterThan(0);
        // This base app has no datasource, so the SQL trace source is absent and the assembler surfaces a
        // clear, neutral warning (no more "not yet captured on Quarkus" copy) while still listing the
        // always-present requests + exceptions sources.
        assertThat(report.path("sources").toString())
                .contains("requests")
                .contains("exceptions")
                .doesNotContain("sql");
        assertThat(report.path("warnings").isArray()).isTrue();
        assertThat(report.path("warnings").toString())
                .as("SQL trace degrades cleanly to a datasource-needed warning")
                .contains("datasource");
        assertThat(report.path("warnings").toString())
                .as("the stale 'not yet captured on Quarkus' copy is gone")
                .doesNotContain("not yet captured");
    }

    /**
     * Quarkus has no framework route template at capture time, so routes resolve from the application's
     * declared JAX-RS mappings, then a masked path, exactly as SQL Trace attributes them on this adapter.
     */
    @Test
    void routeRankingsResolveDeclaredJaxRsRoutesAndMaskEverythingElse() {
        BootUiHttpProbe probe = probe();
        probe.get("/widgets");
        probe.get("/widgets");
        probe.get("/no-such-route/3f2b8c1e-0a4d-4e8b-9c55-1d2e3f4a5b6c?token=secret");

        Response response = probe.get("/bootui/api/http-exchanges/routes");
        assertThat(response.status()).as("routes status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode report = response.json();
        assertThat(report.path("available").asBoolean()).isTrue();
        assertThat(report.path("topPerCriterion").asInt()).isPositive();

        JsonNode window = report.path("window");
        assertThat(window.path("bufferSize").asInt())
                .as("the Quarkus buffer reports its capacity")
                .isPositive();
        assertThat(window.path("evicted").isIntegralNumber())
                .as("the failure-preserving buffer counts its evictions")
                .isTrue();
        assertThat(window.path("evicted").asLong(-1)).isNotNegative();
        assertThat(window.path("summarizedExchanges").asInt()).isGreaterThanOrEqualTo(3);

        JsonNode widgets = null;
        JsonNode masked = null;
        for (JsonNode route : report.path("routes")) {
            assertThat(route.path("route").asText()).doesNotStartWith("/bootui").doesNotContain("?");
            assertThat(route.path("id").asText()).doesNotContain("3f2b8c1e").doesNotContain("secret");
            if ("GET /widgets".equals(route.path("id").asText())) {
                widgets = route;
            }
            if ("GET /no-such-route/{value}".equals(route.path("id").asText())) {
                masked = route;
            }
        }
        assertThat(widgets).as("declared JAX-RS route").isNotNull();
        assertThat(widgets.path("routeSource").asText()).isEqualTo("DECLARED_MAPPING");
        assertThat(widgets.path("requests").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(masked).as("undeclared path grouped by its masked form").isNotNull();
        assertThat(masked.path("routeSource").asText()).isEqualTo("MASKED_PATH");
        assertThat(masked.path("status4xx").asInt()).isGreaterThanOrEqualTo(1);

        Response filtered = probe.get("/bootui/api/http-exchanges?route=GET%20/widgets");
        assertThat(filtered.status()).isEqualTo(200);
        assertThat(filtered.json().path("exchanges").size())
                .isEqualTo(widgets.path("requests").asInt());
        for (JsonNode exchange : filtered.json().path("exchanges")) {
            assertThat(exchange.path("route").asText()).isEqualTo("/widgets");
            assertThat(exchange.path("routeSource").asText()).isEqualTo("DECLARED_MAPPING");
        }

        // Live Activity resolves the slowest request's route with the same declared mappings, so its label and
        // source match the route summary row it links to.
        JsonNode kpis = probe.get("/bootui/api/activity").json().path("kpis");
        String slowestRouteId = kpis.path("slowestEndpointRouteId").asText(null);
        assertThat(slowestRouteId).as("slowest request route id").isNotNull();
        JsonNode rankings = probe.get("/bootui/api/http-exchanges/routes?limit=1&route="
                        + java.net.URLEncoder.encode(slowestRouteId, java.nio.charset.StandardCharsets.UTF_8))
                .json();
        JsonNode slowestRow = null;
        for (JsonNode route : rankings.path("routes")) {
            if (slowestRouteId.equals(route.path("id").asText())) {
                slowestRow = route;
            }
        }
        assertThat(slowestRow).as("route summary row for %s", slowestRouteId).isNotNull();
        assertThat(kpis.path("slowestEndpointRouteSource").asText())
                .isEqualTo(slowestRow.path("routeSource").asText());
        assertThat(kpis.path("slowestEndpointMs").asLong())
                .isEqualTo(slowestRow.path("maxDurationMs").asLong());
    }
}
