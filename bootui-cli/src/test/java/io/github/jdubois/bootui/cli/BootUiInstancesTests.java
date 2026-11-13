package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootUiInstancesTests {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private String overviewInstanceId = "a1b2c3d4";
    private String toolResponse = "{\"beans\":[{\"name\":\"orderService\"}]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), path, auth));

            String body;
            if (path.endsWith("/overview")) {
                body = "{\"applicationName\":\"order-service\",\"frameworkName\":\"Spring Boot\","
                        + "\"run\":{\"instanceId\":\"" + overviewInstanceId
                        + "\",\"runId\":\"run12345\",\"ordinal\":1,\"startedAt\":1700000000000}}";
            } else {
                body = toolResponse;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void prunesDeadProcessAndMismatchedStartTimeLeasesAutomatically() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        Files.createDirectories(instancesDir);

        long currentPid = ProcessHandle.current().pid();
        long currentStart = ProcessHandle.current()
                .info()
                .startInstant()
                .map(Instant::toEpochMilli)
                .orElse(1700000000000L);

        // 1. Live matching lease
        writeLease(
                instancesDir,
                "a1b2c3d4",
                "run00001",
                currentPid,
                currentStart,
                "order-service",
                "Spring Boot",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "/bootui/api",
                1700000000000L);

        // 2. Dead PID lease -> must be deleted
        Path deadFile = writeLease(
                instancesDir,
                "dead0001",
                "run00002",
                Long.MAX_VALUE - 7,
                currentStart,
                "dead-service",
                "Spring Boot",
                "http://127.0.0.1:18080",
                "/bootui/api",
                1700000000000L);

        // 3. Reused PID with mismatched startInstant (> 2s tolerance) -> must be deleted
        Path reusedPidFile = writeLease(
                instancesDir,
                "beef0002",
                "run00003",
                currentPid,
                currentStart - 10_000L,
                "stale-service",
                "Quarkus",
                "http://127.0.0.1:18081",
                "/bootui/api",
                1700000000000L);

        List<BootUiInstances.Instance> live = BootUiInstances.list(instancesDir);

        assertThat(live).singleElement().satisfies(instance -> {
            assertThat(instance.instanceId()).isEqualTo("a1b2c3d4");
            assertThat(instance.appName()).isEqualTo("order-service");
        });
        assertThat(deadFile).doesNotExist();
        assertThat(reusedPidFile).doesNotExist();
    }

    @Test
    void instancesCommandOutputsLiveInstancesInTextAndJson() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        writeCurrentProcessLease(instancesDir, "a1b2c3d4", "order-service", "/bootui/api");

        CliResult textResult = runCli(instancesDir, true, "instances");
        assertThat(textResult.exitCode).isZero();
        assertThat(textResult.out)
                .contains("order-service")
                .contains("a1b2c3d4")
                .contains("Spring Boot");

        CliResult jsonResult = runCli(instancesDir, false, "instances", "--json");
        assertThat(jsonResult.exitCode).isZero();
        JsonValue parsed = JsonValue.parseStrict(jsonResult.out.trim());
        assertThat(parsed.get("instances").values()).singleElement().satisfies(item -> {
            assertThat(item.get("instanceId").asString("")).isEqualTo("a1b2c3d4");
            assertThat(item.get("appName").asString("")).isEqualTo("order-service");
        });
    }

    @Test
    void appOptionVerifiesLoopbackHandshakeWithoutTokenBeforeSendingAuthenticatedToolCall() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        writeCurrentProcessLease(instancesDir, "a1b2c3d4", "order-service", "/custom/bootui/api");

        CliResult result = runCli(instancesDir, true, "--app", "order-service", "--token", "secret-token", "beans");

        assertThat(result.exitCode).isZero();
        assertThat(result.out).contains("orderService");
        assertThat(requests).hasSize(2);
        // 1st request: unauthenticated /overview handshake
        assertThat(requests.get(0).method).isEqualTo("GET");
        assertThat(requests.get(0).path).isEqualTo("/custom/bootui/api/overview");
        assertThat(requests.get(0).authorization)
                .as("handshake must never attach the bearer token")
                .isNull();
        // 2nd request: tool call after handshake verification succeeded
        assertThat(requests.get(1).method).isEqualTo("POST");
        assertThat(requests.get(1).path).isEqualTo("/custom/bootui/api/cli/tools/get_beans");
        assertThat(requests.get(1).authorization).isEqualTo("Bearer secret-token");

        // init --app also verifies handshake and resolves the leased baseUrl + apiPath
        requests.clear();
        CliResult initApp = runCli(instancesDir, true, "init", "--app", "order-service", "--vscode", "--print");
        assertThat(initApp.exitCode).isZero();
        assertThat(JsonValue.parseStrict(initApp.out)
                        .get("servers")
                        .get("bootui")
                        .get("url")
                        .asString(""))
                .isEqualTo("http://127.0.0.1:" + server.getAddress().getPort() + "/custom/bootui/api/mcp");
        assertThat(requests)
                .singleElement()
                .satisfies(req -> assertThat(req.path).isEqualTo("/custom/bootui/api/overview"));
    }

    @Test
    void appOptionRefusesToSendToolOrTokenWhenOverviewInstanceIdMismatchesLease() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        writeCurrentProcessLease(instancesDir, "a1b2c3d4", "order-service", "/bootui/api");
        overviewInstanceId = "imposter9";

        CliResult result = runCli(instancesDir, true, "--app", "order-service", "--token", "secret-token", "beans");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err)
                .contains("Instance identity mismatch")
                .contains("a1b2c3d4")
                .contains("imposter9");
        // Only the unauthenticated /overview request was sent; the tool call and token were never sent!
        assertThat(requests).singleElement().satisfies(req -> {
            assertThat(req.path).isEqualTo("/bootui/api/overview");
            assertThat(req.authorization).isNull();
        });
    }

    @Test
    void appOptionFailsClosedOnDuplicateAppNamesAndPrintsDisambiguatingUrls() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        writeCurrentProcessLease(instancesDir, "a1b2c3d4", "application", "/bootui/api");
        writeCurrentProcessLease(instancesDir, "e5f60718", "application", "/v2/bootui/api");

        CliResult ambiguous = runCli(instancesDir, true, "--app", "application", "beans");
        assertThat(ambiguous.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(ambiguous.err)
                .contains("Multiple running BootUI instances match 'application'")
                .contains("a1b2c3d4")
                .contains("e5f60718")
                .contains("--url http://127.0.0.1:" + server.getAddress().getPort())
                .contains("--api-path /v2/bootui/api");
        assertThat(requests).isEmpty();

        // Disambiguating by unique instanceId prefix succeeds
        overviewInstanceId = "a1b2c3d4";
        CliResult disambiguated = runCli(instancesDir, true, "--app", "a1b2", "beans");
        assertThat(disambiguated.exitCode).isZero();
    }

    @Test
    void rejectsCombiningAppAndUrlOptionsOrBlankAppSelector() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        writeCurrentProcessLease(instancesDir, "a1b2c3d4", "order-service", "/bootui/api");

        CliResult result =
                runCli(instancesDir, true, "--app", "order-service", "--url", "http://localhost:8080", "beans");
        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("Choose --app or --url/--api-path, not both.");
        assertThat(requests).isEmpty();

        CliResult apiPathOverride =
                runCli(instancesDir, true, "--app", "order-service", "--api-path", "/other/api", "beans");
        assertThat(apiPathOverride.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(apiPathOverride.err).contains("Choose --app or --url/--api-path, not both.");
        assertThat(requests).isEmpty();

        CliResult blankApp = runCli(instancesDir, true, "--app", "", "beans");
        assertThat(blankApp.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(blankApp.err).contains("--app must not be blank.");
        assertThat(requests).isEmpty();

        // Bypass ambient HTTP proxy when --app is used
        String oldProxyHost = System.getProperty("http.proxyHost");
        String oldProxyPort = System.getProperty("http.proxyPort");
        String oldNonProxy = System.getProperty("http.nonProxyHosts");
        try {
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", "1");
            System.setProperty("http.nonProxyHosts", "");
            CliResult proxied = runCli(instancesDir, true, "--app", "order-service", "beans");
            assertThat(proxied.exitCode).isZero();
        } finally {
            if (oldProxyHost == null) {
                System.clearProperty("http.proxyHost");
            } else {
                System.setProperty("http.proxyHost", oldProxyHost);
            }
            if (oldProxyPort == null) {
                System.clearProperty("http.proxyPort");
            } else {
                System.setProperty("http.proxyPort", oldProxyPort);
            }
            if (oldNonProxy == null) {
                System.clearProperty("http.nonProxyHosts");
            } else {
                System.setProperty("http.nonProxyHosts", oldNonProxy);
            }
        }
    }

    @Test
    void rejectsTamperedLeaseFilesWithMismatchedFilenameOrSmuggledToken() throws IOException {
        Path instancesDir = tempDir.resolve("instances");
        Files.createDirectories(instancesDir);
        long pid = ProcessHandle.current().pid();
        long start = ProcessHandle.current()
                .info()
                .startInstant()
                .map(Instant::toEpochMilli)
                .orElse(1700000000000L);

        // Filename stem does not match JSON instanceId
        Path mismatchedStem = instancesDir.resolve("wrongname.json");
        Files.writeString(
                mismatchedStem,
                "{\"instanceId\":\"a1b2c3d4\",\"runId\":\"run1\",\"pid\":" + pid
                        + ",\"processStartEpochMillis\":" + start
                        + ",\"appName\":\"app\",\"framework\":\"Spring Boot\","
                        + "\"baseUrl\":\"http://localhost:8080\",\"apiPath\":\"/bootui/api\",\"startedAt\":1700000000000}");

        // Lease containing a token field
        Path withToken = instancesDir.resolve("a1b2c3d4.json");
        Files.writeString(
                withToken,
                "{\"instanceId\":\"a1b2c3d4\",\"runId\":\"run1\",\"pid\":" + pid
                        + ",\"processStartEpochMillis\":" + start
                        + ",\"appName\":\"app\",\"framework\":\"Spring Boot\","
                        + "\"baseUrl\":\"http://localhost:8080\",\"apiPath\":\"/bootui/api\","
                        + "\"startedAt\":1700000000000,\"token\":\"secret\"}");

        assertThat(BootUiInstances.list(instancesDir)).isEmpty();
        assertThat(mismatchedStem).doesNotExist();
        assertThat(withToken).doesNotExist();

        assertThatThrownBy(() -> BootUiInstances.resolve(instancesDir, "app", Duration.ofSeconds(2), BootUiClient::new))
                .isInstanceOf(BootUiClientException.class)
                .hasMessageContaining("No running BootUI instance found");
    }

    private Path writeCurrentProcessLease(Path instancesDir, String instanceId, String appName, String apiPath)
            throws IOException {
        long currentPid = ProcessHandle.current().pid();
        long currentStart = ProcessHandle.current()
                .info()
                .startInstant()
                .map(Instant::toEpochMilli)
                .orElse(1700000000000L);
        return writeLease(
                instancesDir,
                instanceId,
                "run12345",
                currentPid,
                currentStart,
                appName,
                "Spring Boot",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                apiPath,
                1700000000000L);
    }

    private static Path writeLease(
            Path instancesDir,
            String instanceId,
            String runId,
            long pid,
            long processStartEpochMillis,
            String appName,
            String framework,
            String baseUrl,
            String apiPath,
            long startedAt)
            throws IOException {
        Files.createDirectories(instancesDir);
        Path target = instancesDir.resolve(instanceId + ".json");
        String json = "{\n"
                + "  \"instanceId\": \"" + instanceId + "\",\n"
                + "  \"runId\": \"" + runId + "\",\n"
                + "  \"pid\": " + pid + ",\n"
                + "  \"processStartEpochMillis\": " + processStartEpochMillis + ",\n"
                + "  \"appName\": \"" + appName + "\",\n"
                + "  \"framework\": \"" + framework + "\",\n"
                + "  \"baseUrl\": \"" + baseUrl + "\",\n"
                + "  \"apiPath\": \"" + apiPath + "\",\n"
                + "  \"startedAt\": " + startedAt + "\n"
                + "}\n";
        Files.writeString(target, json, StandardCharsets.UTF_8);
        return target;
    }

    private static CliResult runCli(Path instancesDir, boolean terminal, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = BootUiCli.run(
                args,
                Map.of(BootUiInstances.BOOTUI_INSTANCES_DIR_ENV, instancesDir.toString()),
                terminal,
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        return new CliResult(exitCode, out.toString(), err.toString());
    }

    private record RecordedRequest(String method, String path, String authorization) {}

    private record CliResult(int exitCode, String out, String err) {}
}
