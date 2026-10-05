package io.github.jdubois.bootui.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the transport against a real loopback HTTP server rather than a mocked {@code HttpClient}, so
 * header handling, status mapping, and body pass-through are tested as the wire actually delivers them.
 */
class BootUiClientTests {

    private HttpServer server;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private int status = 200;
    private String responseBody = "{}";
    private String contentType = "application/json";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private BootUiClient client() {
        return new BootUiClient(new BootUiClientOptions(baseUrl(), "/bootui/api", null, Duration.ofSeconds(5)));
    }

    private BootUiClient clientWithToken(String token) {
        return new BootUiClient(new BootUiClientOptions(baseUrl(), "/bootui/api", token, Duration.ofSeconds(5)));
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void readsTheCatalogFromTheCommandLineEndpoint() {
        responseBody = """
                {"enabled":true,"serverName":"bootui","serverVersion":"1.15.0","endpoint":"/bootui/api/cli",
                 "maxResults":200,"toolCount":1,
                 "tools":[{"name":"get_config","description":"Search configuration.","panel":"config","action":false,
                           "schema":"QUERY_LIMIT","arguments":["query","limit"],"panelEnabled":true,"panelReadOnly":false}]}
                """;

        BootUiCatalog catalog = client().catalog();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("GET");
            assertThat(request.path).isEqualTo("/bootui/api/cli");
        });
        assertThat(catalog.enabled()).isTrue();
        assertThat(catalog.serverVersion()).isEqualTo("1.15.0");
        assertThat(catalog.maxResults()).isEqualTo(200);
        assertThat(catalog.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_config");
            assertThat(tool.panel()).isEqualTo("config");
            assertThat(tool.schema()).isEqualTo("QUERY_LIMIT");
            assertThat(tool.arguments()).containsExactly("query", "limit");
            assertThat(tool.refused()).isFalse();
        });
    }

    @Test
    void aToolIsReportedRefusedWhenItsPanelIsOffOrTheActionIsReadOnly() {
        BootUiCatalog.CatalogTool disabled =
                new BootUiCatalog.CatalogTool("a", "", "p", false, "NONE", List.of(), false, false);
        BootUiCatalog.CatalogTool readOnlyAction =
                new BootUiCatalog.CatalogTool("b", "", "p", true, "NONE", List.of(), true, true);
        BootUiCatalog.CatalogTool readOnlyRead =
                new BootUiCatalog.CatalogTool("c", "", "p", false, "NONE", List.of(), true, true);

        assertThat(disabled.refused()).isTrue();
        assertThat(readOnlyAction.refused()).isTrue();
        assertThat(readOnlyRead.refused()).isFalse();
    }

    @Test
    void sendsOnlyTheArgumentsSuppliedSoUnusedOnesAreNotRejected() {
        responseBody = "{\"items\":[]}";

        client().invoke("get_config", "spring", 5, null);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.path).isEqualTo("/bootui/api/cli/tools/get_config");
            assertThat(request.body).isEqualTo("{\"query\":\"spring\",\"limit\":5}");
        });
    }

    @Test
    void forwardsAdvisorSnapshotAndPagingArgumentsWithoutADtoDependency() {
        java.util.Map<String, JsonValue> arguments = new java.util.LinkedHashMap<>();
        arguments.put("id", JsonValue.of("ARCH-SPRING-004"));
        arguments.put("scanId", JsonValue.of("scan-1"));
        arguments.put("offset", JsonValue.of(22));
        arguments.put("limit", JsonValue.of(7));
        responseBody = "{\"scanId\":\"scan-1\",\"ruleId\":\"ARCH-SPRING-004\",\"violations\":[\"detail\"],"
                + "\"page\":{\"offset\":22,\"limit\":7,\"returned\":1,\"hasMore\":false}}";

        ToolResult result = client().invoke("get_architecture_rule_violations", arguments);

        assertThat(result.successful()).isTrue();
        assertThat(result.rawBody()).isEqualTo(responseBody);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path).isEqualTo("/bootui/api/cli/tools/get_architecture_rule_violations");
            assertThat(request.body)
                    .isEqualTo("{\"id\":\"ARCH-SPRING-004\",\"scanId\":\"scan-1\",\"offset\":22,\"limit\":7}");
        });
    }

    @Test
    void sendsAnEmptyObjectWhenATakesNoArguments() {
        client().invoke("get_overview");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.body).isEqualTo("{}"));
    }

    @Test
    void keepsTheResponseBodyVerbatimSoJsonOutputIsExactlyWhatTheServerSent() {
        responseBody = "{\"b\":1,\"a\":[2,3]}";

        ToolResult result = client().invoke("get_overview");

        assertThat(result.successful()).isTrue();
        assertThat(result.rawBody()).isEqualTo(responseBody);
        assertThat(result.payload().get("a").size()).isEqualTo(2);
    }

    @Test
    void mapsEveryRefusalStatusToAnOutcomeTheCallerCanBranchOn() {
        assertThat(outcomeFor(200)).isEqualTo(ToolOutcome.SUCCESS);
        assertThat(outcomeFor(400)).isEqualTo(ToolOutcome.INVALID_REQUEST);
        assertThat(outcomeFor(403)).isEqualTo(ToolOutcome.REFUSED_BY_POLICY);
        assertThat(outcomeFor(404)).isEqualTo(ToolOutcome.UNKNOWN_TOOL);
        assertThat(outcomeFor(409)).isEqualTo(ToolOutcome.BUSY);
        assertThat(outcomeFor(429)).isEqualTo(ToolOutcome.BUSY);
        assertThat(outcomeFor(503)).isEqualTo(ToolOutcome.ENDPOINT_DISABLED);
        assertThat(outcomeFor(504)).isEqualTo(ToolOutcome.TIMED_OUT);
        assertThat(outcomeFor(500)).isEqualTo(ToolOutcome.SERVER_ERROR);
    }

    private ToolOutcome outcomeFor(int httpStatus) {
        status = httpStatus;
        responseBody = "{\"error\":\"refused\"}";
        return client().invoke("get_overview").outcome();
    }

    @Test
    void surfacesTheServersErrorMessage() {
        status = 403;
        responseBody = "{\"error\":\"Panel 'copilot' is disabled\"}";

        ToolResult result = client().invoke("get_copilot_sessions");

        assertThat(result.error()).isEqualTo("Panel 'copilot' is disabled");
        assertThat(result.errorMessage()).isEqualTo("Panel 'copilot' is disabled");
    }

    @Test
    void describesTheFailureWhenTheServerSendsNoErrorMessage() {
        status = 500;
        responseBody = "";

        ToolResult result = client().invoke("get_overview");

        assertThat(result.errorMessage()).isEqualTo("Request failed with HTTP 500");
    }

    @Test
    void sendsTheBearerTokenOnlyWhenOneIsConfigured() {
        clientWithToken("secret").invoke("get_overview");
        client().invoke("get_overview");

        assertThat(requests.get(0).authorization).isEqualTo("Bearer secret");
        assertThat(requests.get(1).authorization).isNull();
    }

    @Test
    void saysSoWhenNothingIsListeningRatherThanLeakingAConnectException() {
        BootUiClient unreachable = new BootUiClient(
                new BootUiClientOptions("http://localhost:1", "/bootui/api", null, Duration.ofSeconds(2)));

        assertThatThrownBy(unreachable::catalog)
                .isInstanceOf(BootUiClientException.class)
                .hasMessageContaining("Cannot reach BootUI")
                .hasMessageContaining("--url");
    }

    @Test
    void explainsA404OnTheCatalogAsAMissingEndpointRatherThanAnUnknownTool() {
        status = 404;
        responseBody = "";

        assertThatThrownBy(() -> client().catalog())
                .isInstanceOf(BootUiClientException.class)
                .hasMessageContaining("No BootUI command-line endpoint")
                .hasMessageContaining("--api-path");
    }

    @Test
    void refusesASuccessfulResponseThatIsNotJsonInsteadOfRenderingIt() {
        contentType = "text/html";
        responseBody = "<html><body>502 Bad Gateway</body></html>";

        assertThatThrownBy(() -> client().invoke("get_overview"))
                .isInstanceOf(BootUiClientException.class)
                .hasMessageContaining("is not JSON");
    }

    @Test
    void aNonJsonErrorBodyStillYieldsTheStatusOutcomeRatherThanFailingTheCall() {
        status = 502;
        contentType = "text/html";
        responseBody = "<html>bad gateway</html>";

        ToolResult result = client().invoke("get_overview");

        assertThat(result.outcome()).isEqualTo(ToolOutcome.SERVER_ERROR);
        assertThat(result.errorMessage()).isEqualTo("Request failed with HTTP 502");
    }

    @Test
    void explainsAnAuthenticationRefusalOnTheCatalogInTermsOfTheFlagsThatFixIt() {
        status = 401;
        responseBody = "{}";

        assertThatThrownBy(() -> client().catalog())
                .isInstanceOf(BootUiClientException.class)
                .hasMessageContaining("--token");
    }

    @Test
    void readsAndWritesPanelEndpointsForTheCommandsThatAreNotToolCalls() {
        responseBody = "{\"enabled\":true}";

        JsonValue read = client().get("/mcp-server");
        JsonValue written = client().post("/mcp-server/toggle", "{\"enabled\":false}");

        assertThat(read.get("enabled").asBoolean(false)).isTrue();
        assertThat(written.get("enabled").asBoolean(false)).isTrue();
        assertThat(requests.get(0).path).isEqualTo("/bootui/api/mcp-server");
        assertThat(requests.get(1).method).isEqualTo("POST");
        assertThat(requests.get(1).body).isEqualTo("{\"enabled\":false}");
    }

    private record RecordedRequest(String method, String path, String authorization, String body) {}
}
