package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the CLI end to end against a stub of the command-line endpoint.
 *
 * <p>Uses a real HTTP server rather than a mocked client so the parts most likely to be wrong — how a
 * command's flags become a request body, and how a status becomes an exit code — are actually exercised.
 */
class BootUiCliTests {

    private HttpServer server;
    private final List<Recorded> requests = new ArrayList<>();
    private int status = 200;
    private String responseBody = "{}";
    private String catalogOverride;
    private String panelsOverride;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    header(exchange, "Authorization")));
            // A failed tool call makes the CLI ask the same instance two further questions: whether there is
            // a command-line endpoint at all, and why a panel is missing. Only a test that cares about those
            // answers sets them, so every other test keeps the single-answer stub it was written against.
            String path = exchange.getRequestURI().getPath();
            int answerStatus = status;
            String answerBody = responseBody;
            if (catalogOverride != null && path.endsWith("/cli")) {
                answerStatus = 200;
                answerBody = catalogOverride;
            } else if (panelsOverride != null && path.endsWith("/panels")) {
                answerStatus = 200;
                answerBody = panelsOverride;
            }
            byte[] body = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answerStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aToolCommandPostsToItsToolAndPrintsThePayload() {
        responseBody = "{\"beans\":[{\"name\":\"dataSource\",\"scope\":\"singleton\"}]}";

        Result result = run("beans");

        assertThat(result.exitCode).isZero();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.path).isEqualTo("/bootui/api/cli/tools/get_beans");
            assertThat(request.body).isEqualTo("{}");
        });
        assertThat(result.out).contains("dataSource").contains("singleton");
    }

    @Test
    void argumentsAreSentOnlyWhenGivenSoTheEndpointDoesNotRefuseTheCall() {
        run("beans", "--query", "data", "--limit", "5");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.body).isEqualTo("{\"query\":\"data\",\"limit\":5}"));
    }

    @Test
    void aPositionalIdBecomesTheIdArgument() {
        run("exceptions", "show", "abc-123");

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path).isEqualTo("/bootui/api/cli/tools/get_exception_detail");
            assertThat(request.body).isEqualTo("{\"id\":\"abc-123\"}");
        });
    }

    @Test
    void requestProfileIsATopLevelCommandTakingTheActivityEntryId() {
        // `bootui activity` is already a command, so the profile cannot be `bootui activity profile`.
        run("request-profile", "req-42");

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path).isEqualTo("/bootui/api/cli/tools/get_request_profile");
            assertThat(request.body).isEqualTo("{\"id\":\"req-42\"}");
        });
    }

    @Test
    void jsonModePrintsTheServerBodyVerbatim() {
        responseBody = "{\"a\":1.50,\"b\":[true]}";

        Result result = run("beans", "--json");

        assertThat(result.out.strip()).isEqualTo(responseBody);
    }

    @Test
    void outputIsJsonWhenNothingLooksLikeATerminal() {
        responseBody = "{\"a\":1}";

        Result result = runPiped("beans");

        assertThat(result.out.strip()).isEqualTo(responseBody);
    }

    @Test
    void aRefusedToolExitsDistinctlyFromAFailedRequest() {
        status = 403;
        responseBody = "{\"error\":\"Panel 'beans' is disabled\"}";
        catalogOverride = "{\"enabled\":true,\"tools\":[]}";

        Result result = run("beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.REFUSED);
        assertThat(result.err).contains("Panel 'beans' is disabled").contains("beans");
        assertThat(result.out).isEmpty();
    }

    @Test
    void aNonPositiveTimeoutIsRejectedRatherThanInterpreted() {
        // A zero timeout is accepted by Duration, so without this guard the call either fails instantly or
        // waits forever depending on the transport, and the caller is told neither.
        Result result = run("--timeout", "0", "beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("--timeout").contains("positive");
    }

    @Test
    void aForbiddenAnswerFromSomethingOtherThanBootUiIsNotAPolicyRefusal() {
        // Spring Security answers 403 to a POST at an unknown path, so a typo in --api-path, or a --url
        // aimed at another application, reaches here looking exactly like a disabled panel. Exit 2 would
        // tell a CI gate to skip, so a misconfigured target would pass instead of failing. No catalog
        // override: the follow-up question gets the same 403, which is how the CLI can tell.
        status = 403;
        responseBody = "{\"timestamp\":\"now\",\"status\":403,\"error\":\"Forbidden\",\"path\":\"/nope\"}";

        Result result = run("beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("not a BootUI policy refusal").doesNotContain("panel 'beans'");
    }

    @Test
    void aRejectedTokenIsAFailedRequestRatherThanAPolicyRefusal() {
        // 401 means the caller was not accepted, which says nothing about how the target's panels are
        // configured. Reporting it as a refusal would make a CI job that forgot --token skip silently.
        status = 401;
        responseBody = "{\"error\":\"Authentication required\"}";

        Result result = run("beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err)
                .contains("Authentication required")
                .contains("--token")
                .doesNotContain("panel '");
    }

    @Test
    void aDisabledEndpointSaysWhichPropertyTurnsItOn() {
        status = 503;
        responseBody = "{\"error\":\"disabled\"}";

        Result result = run("beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.REFUSED);
        assertThat(result.err).contains("bootui.cli.enabled=true");
    }

    @Test
    void aToolMissingFromThisApplicationSaysWhichStacksHaveIt() {
        status = 404;
        responseBody = "{\"error\":\"Unknown tool\"}";
        catalogOverride = "{\"enabled\":true,\"tools\":[]}";

        Result result = run("http", "sessions");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("get_http_sessions").contains("spring mvc");
    }

    @Test
    void aToolMissingBecauseItsPanelIsUnavailableSaysWhyRatherThanGuessing() {
        // get_kafka_activity is advertised on every stack, so the version guess would fire — and be wrong.
        // The application already knows the real reason, so it is asked instead of guessed at.
        status = 404;
        responseBody = "{\"error\":\"Unknown tool\"}";
        catalogOverride = "{\"enabled\":true,\"tools\":[]}";
        panelsOverride = "{\"platform\":\"spring-boot\",\"panels\":[{\"id\":\"kafka\",\"available\":false,"
                + "\"unavailableReason\":\"No KafkaTemplate bean is available\"}]}";

        Result result = run("kafka");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err)
                .contains("get_kafka_activity")
                .contains("No KafkaTemplate bean is available")
                .doesNotContain("older BootUI version");
    }

    @Test
    void aPanelLookupThatFailsStillLeavesTheRealFailureReported() {
        // The hint is a courtesy on a path that has already failed. Losing it must not cost the reader the
        // failure itself, so /panels answering 404 here falls back rather than throwing.
        status = 404;
        responseBody = "{\"error\":\"Unknown tool\"}";
        catalogOverride = "{\"enabled\":true,\"tools\":[]}";

        Result result = run("kafka");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("get_kafka_activity").contains("older BootUI version");
    }

    @Test
    void anInvalidArgumentIsAnErrorRatherThanARefusal() {
        status = 400;
        responseBody = "{\"error\":\"Argument 'limit' must be at least 1\"}";

        Result result = run("beans", "--limit", "0");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("must be at least 1");
    }

    @Test
    void anUnreachableApplicationFailsWithAMessageNamingTheFlagThatFixesIt() {
        Result result = runAt("http://127.0.0.1:1", "beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("--url");
    }

    @Test
    void anUnknownCommandIsAUsageErrorNotACall() {
        Result result = run("definitely-not-a-command");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(requests).isEmpty();
    }

    @Test
    void aCommandGroupWithoutASubcommandFailsRatherThanSucceedingSilently() {
        Result result = run("memory");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(requests).isEmpty();
    }

    @Test
    void helpNeedsNoRunningApplication() {
        Result result = run("--help");

        assertThat(result.exitCode).isEqualTo(ExitCodes.SUCCESS);
        assertThat(result.out).contains("beans").contains("--url");
        assertThat(requests).isEmpty();
    }

    @Test
    void commandHelpNamesTheMcpToolItProjects() {
        Result result = run("beans", "--help");

        assertThat(result.exitCode).isEqualTo(ExitCodes.SUCCESS);
        assertThat(result.out).contains("MCP tool: get_beans");
    }

    @Test
    void theTokenFlagIsSentAsABearerHeader() {
        run("beans", "--token", "s3cret");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.authorization).isEqualTo("Bearer s3cret"));
    }

    @Test
    void theEnvironmentSuppliesTheTokenWhenTheFlagIsAbsent() {
        runWith(Map.of("BOOTUI_URL", baseUrl(), "BOOTUI_TOKEN", "from-env"), true, "beans");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.authorization).isEqualTo("Bearer from-env"));
    }

    @Test
    void aGlobalFlagWorksAfterTheCommandToo() {
        responseBody = "{\"a\":1}";

        Result result = run("beans", "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.out.strip()).isEqualTo(responseBody);
    }

    @Test
    void toolsReadsWhatThisApplicationAdvertises() {
        responseBody = "{\"enabled\":true,\"serverName\":\"bootui\",\"serverVersion\":\"1.15.0\","
                + "\"endpoint\":\"/bootui/api/cli\",\"maxResults\":100,\"tools\":["
                + "{\"name\":\"get_beans\",\"description\":\"Beans\",\"panel\":\"beans\",\"action\":false,"
                + "\"schema\":\"QUERY_LIMIT\",\"arguments\":[\"query\",\"limit\"],\"panelEnabled\":true,"
                + "\"panelReadOnly\":false}]}";

        Result result = run("tools");

        assertThat(result.exitCode).isZero();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("GET");
            assertThat(request.path).isEqualTo("/bootui/api/cli");
        });
        assertThat(result.out).contains("get_beans").contains("beans").contains("ready");
    }

    @Test
    void toolsReportsATooltheApplicationWouldRefuse() {
        responseBody = "{\"enabled\":true,\"tools\":[{\"name\":\"trigger_gc\",\"panel\":\"memory\","
                + "\"action\":true,\"schema\":\"NONE\",\"arguments\":[],\"panelEnabled\":true,"
                + "\"panelReadOnly\":true}]}";

        Result result = run("tools");

        assertThat(result.out).contains("read-only");
    }

    @Test
    void mcpEnableTogglesThePanelRatherThanReachingPastIt() {
        responseBody = "{\"enabled\":true}";

        Result result = run("mcp", "enable");

        assertThat(result.exitCode).isZero();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.path).isEqualTo("/bootui/api/mcp-server/toggle");
            assertThat(request.body).isEqualTo("{\"enabled\":true}");
        });
    }

    @Test
    void mcpDisableSendsTheOppositeState() {
        responseBody = "{\"enabled\":false}";

        run("mcp", "disable");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.body).isEqualTo("{\"enabled\":false}"));
    }

    @Test
    void aCustomApiPathIsHonouredEverywhere() {
        responseBody = "{\"a\":1}";

        run("beans", "--api-path", "/admin/bootui/api");

        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request.path).isEqualTo("/admin/bootui/api/cli/tools/get_beans"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Result run(String... args) {
        return runAt(baseUrl(), args);
    }

    private Result runPiped(String... args) {
        return runWith(Map.of("BOOTUI_URL", baseUrl()), false, args);
    }

    private Result runAt(String url, String... args) {
        List<String> full = new ArrayList<>();
        full.add("--url");
        full.add(url);
        full.addAll(List.of(args));
        return runWith(Map.of(), true, full.toArray(new String[0]));
    }

    private Result runWith(Map<String, String> environment, boolean terminal, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode =
                BootUiCli.run(args, environment, terminal, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Result(exitCode, out.toString(), err.toString());
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private record Recorded(String method, String path, String body, String authorization) {}

    private record Result(int exitCode, String out, String err) {}
}
