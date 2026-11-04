package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.cli.BootUiCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiAssessmentRunnerTests {

    @TempDir
    Path temp;

    private HttpServer server;
    private boolean finding;
    private String currentInstance = "a1b2c3d4";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void attachesThenAssessesAConsistentLoopbackTargetAndWritesBoundedReports() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");

        assertThat(runCli(
                        "ci",
                        "attach",
                        "--url",
                        url(),
                        "--assessment-id",
                        "test-assessment",
                        "--policy",
                        policy.toString(),
                        "--output",
                        witness.toString()))
                .isZero();
        assertThat(Files.readString(witness)).contains("\"instanceId\": \"a1b2c3d4\"");
        assertThat(runCli(
                        "ci",
                        "assess",
                        "--url",
                        url(),
                        "--assessment-id",
                        "test-assessment",
                        "--policy",
                        policy.toString(),
                        "--witness",
                        witness.toString(),
                        "--output-dir",
                        output.toString()))
                .isZero();
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("\"status\": \"PASS\"");
        assertThat(Files.readString(output.resolve("junit.xml"))).contains("<testsuite");
    }

    @Test
    void recordsAThresholdViolationWithoutPublishingRawFindingText() throws Exception {
        finding = true;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);

        for (Path report :
                List.of(output.resolve("bundle.json"), output.resolve("summary.md"), output.resolve("junit.xml"))) {
            assertThat(Files.readString(report)).doesNotContain("private-finding-payload");
        }
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("\"status\": \"VIOLATION\"");
    }

    @Test
    void rejectsAChangedRunIdentityAfterTheWorkloadAndPublishesAnError() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);
        assertThat(runner.attach()).isZero();

        currentInstance = "b1b2c3d4";
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("\"status\": \"NOT_ASSESSED\"");
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("IDENTITY_CHANGED");
    }

    @Test
    void preservesEarlierFindingsButLetsACollectionErrorTakeExitPrecedence() throws Exception {
        finding = true;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"high"},
                  "spring":{"required":true,"failOn":"high"}
                }}
                """);
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);
        assertThat(runner.attach()).isZero();

        assertThat(runner.assess()).isEqualTo(1);
        String bundle = Files.readString(output.resolve("bundle.json"));
        assertThat(bundle).contains("\"status\": \"NOT_ASSESSED\"");
        assertThat(bundle).contains("\"findingCount\": 1");
        assertThat(bundle).contains("\"code\": \"SCAN_FAILED\"");
        String junit = Files.readString(output.resolve("junit.xml"));
        assertThat(junit).contains("<failure", "<error");
    }

    @Test
    void rejectsUnknownAndDuplicatePolicyKeys() {
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},"allowPartial":true}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown policy key");
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}}}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strict JSON");
    }

    @Test
    void acceptsOnlyLiteralLoopbackTargetsAndAPathOnlyApiMount() {
        assertThatThrownBy(() -> new CiAssessmentRunner(
                        "http://localhost:8080",
                        "/bootui/api",
                        5,
                        "assessment",
                        temp.resolve("policy.json"),
                        temp.resolve("witness"),
                        temp.resolve("out")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal loopback");
        assertThatThrownBy(() -> new CiAssessmentRunner(
                        "http://127.0.0.1:8080",
                        "/bootui/api?next=/other",
                        5,
                        "assessment",
                        temp.resolve("policy.json"),
                        temp.resolve("witness"),
                        temp.resolve("out")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without query");
    }

    private CiAssessmentRunner runner(Path policy, Path witness, Path output) {
        return new CiAssessmentRunner(url(), "/bootui/api", 5, "test-assessment", policy, witness, output);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static int runCli(String... arguments) {
        StringWriter output = new StringWriter();
        return BootUiCli.run(arguments, Map.of(), false, new PrintWriter(output, true), new PrintWriter(output, true));
    }

    private Path writePolicy() throws IOException {
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}}}
                """);
        return policy;
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.endsWith("/cli")) {
            body = catalog();
        } else if (path.endsWith("/overview")) {
            body = overview();
        } else if (path.endsWith("/tools/architecture_scan")) {
            body = """
                    {"reportTool":"get_architecture_report","scan":{"status":"SCANNED"},
                     "violationDetails":{"scanId":"scan-12345678"}}
                    """;
        } else if (path.endsWith("/tools/get_architecture_report")) {
            body = report();
        } else if (path.endsWith("/tools/spring_scan")) {
            body = "{}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length());
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        } else if (path.endsWith("/tools/get_architecture_rule_violations")) {
            body = detailPage();
        } else {
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String catalog() {
        List<String> tools = new ArrayList<>();
        tools.add(tool("architecture_scan", true, "NONE", List.of()));
        tools.add(tool("get_architecture_report", false, "NONE", List.of()));
        tools.add(tool(
                "get_architecture_rule_violations",
                false,
                "RULE_VIOLATIONS",
                List.of("id", "scanId", "offset", "limit")));
        tools.add(tool("spring_scan", true, "NONE", List.of()));
        tools.add(tool("get_spring_report", false, "NONE", List.of()));
        tools.add(tool(
                "get_spring_rule_violations", false, "RULE_VIOLATIONS", List.of("id", "scanId", "offset", "limit")));
        return """
                {"serverName":"bootui","serverVersion":"2.2.0","enabled":true,"maxResults":200,"tools":[%s]}
                """.formatted(String.join(",", tools));
    }

    private static String tool(String name, boolean action, String schema, List<String> arguments) {
        String panel = name.startsWith("spring_") || name.startsWith("get_spring_") ? "spring" : "architecture";
        return """
                {"name":"%s","description":"test","panel":"%s","action":%s,
                 "schema":"%s","arguments":[%s],"panelEnabled":true,"panelReadOnly":false}
                """.formatted(
                        name,
                        panel,
                        action,
                        schema,
                        arguments.stream()
                                .map(value -> "\"" + value + "\"")
                                .reduce((a, b) -> a + "," + b)
                                .orElse(""));
    }

    private String overview() {
        return """
                {"bootUiVersion":"2.2.0","frameworkName":"Spring Boot",
                 "run":{"instanceId":"%s","runId":"0123abcd"}}
                """.formatted(currentInstance);
    }

    private String report() {
        String results = finding ? """
                  [{"id":"ARCH-001","severity":"HIGH","violationCount":1,"dismissed":false,"suppressedCount":0}]
                  """ : "[]";
        return """
                {"classesAnalyzed":3,"rulesEvaluated":5,"violationsFound":%s,
                 "scan":{"status":"SCANNED"},"results":%s,"analysisErrors":[],
                 "evidence":{"usable":true,"coverageComplete":true,"limitations":[]},
                 "violationDetails":{"scanId":"scan-12345678","total":%s,"retained":%s,"truncated":false}}
                """.formatted(finding ? "1" : "0", results, finding ? "1" : "0", finding ? "1" : "0");
    }

    private String detailPage() {
        return """
                {"scanId":"scan-12345678","ruleId":"ARCH-001","violationCount":1,"retainedCount":1,
                 "truncated":false,"violations":["private-finding-payload"],
                 "page":{"total":1,"matched":1,"offset":0,"limit":200,"returned":1,"hasMore":false},
                 "identities":[],"suppressed":[],"suppressedCount":0}
                """;
    }
}
