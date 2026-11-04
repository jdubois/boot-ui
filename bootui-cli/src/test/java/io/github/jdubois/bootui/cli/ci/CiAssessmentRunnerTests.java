package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.cli.BootUiCli;
import io.github.jdubois.bootui.client.JsonValue;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CiAssessmentRunnerTests {

    @TempDir
    Path temp;

    private HttpServer server;
    private boolean finding;
    private int findingRuleCount = 1;
    private boolean springFails = true;
    private int findingCount = 1;
    private int rulesEvaluated = 5;
    private String databaseDiagnosticLevel = "INFO";
    private int databaseRulesSkipped = 4;
    private int databaseRulesErrored;
    private String reportSeverityCounts;
    private boolean mismatchScanSummary;
    private boolean mismatchSecondPage;
    private int detailRequests;
    private Path obstructOutput;
    private int overviewRequests;
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
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
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
    void acceptsMultipleFindingsForOneRule() throws Exception {
        finding = true;
        findingCount = 2;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"findingCount\": 2", "\"status\": \"VIOLATION\"");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(3);
    }

    @Test
    void retrievesAllPagesAndRejectsAnInconsistentSecondPage() throws Exception {
        finding = true;
        findingCount = 203;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        CiAssessmentRunner first = runner(policy, witness, temp.resolve("first"));
        assertThat(first.attach()).isZero();
        assertThat(first.assess()).isEqualTo(3);
        assertThat(detailRequests).isEqualTo(2);
        assertThat(Files.readString(temp.resolve("first/bundle.json"))).contains("\"findingCount\": 203");
        assertThat(CiBundleEvaluator.evaluate(temp.resolve("first/bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(3);

        mismatchSecondPage = true;
        CiAssessmentRunner second = runner(policy, witness, temp.resolve("second"));
        assertThat(second.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("second/bundle.json"))).contains("DETAIL_PAGE_INCONSISTENT");
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
    void createsMissingParentsForAFreshAssessmentOutputDirectory() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("new-parent/assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(Files.isRegularFile(output.resolve("bundle.json"))).isTrue();
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
    void acceptsNeutralDatabaseSkipsButRejectsWarningDiagnostics() throws Exception {
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"database-advisor":{"required":true,"failOn":"high"}}}
                """);
        Path witness = temp.resolve("witness.json");
        CiAssessmentRunner passing = runner(policy, witness, temp.resolve("passing"));
        assertThat(passing.attach()).isZero();
        assertThat(passing.assess()).isZero();
        assertThat(Files.readString(temp.resolve("passing/bundle.json"))).contains("\"rulesSkipped\": 4");
        assertThat(CiBundleEvaluator.evaluate(temp.resolve("passing/bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
        assertThat(Files.readString(temp.resolve("passing/summary.md"))).contains("Rules skipped", "| 4 |");

        databaseDiagnosticLevel = "WARNING";
        CiAssessmentRunner warning = runner(policy, witness, temp.resolve("warning"));
        assertThat(warning.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("warning/bundle.json"))).contains("ADVISOR_ERRORS");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void rejectsDatabaseReportsWithAllOrTooManyRulesSkipped(int skipped) throws Exception {
        databaseRulesSkipped = skipped;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"database-advisor":{"required":true,"failOn":"high"}}}
                """);
        CiAssessmentRunner runner = runner(policy, temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("COUNTS_INCONSISTENT");
    }

    @Test
    void rejectsAReportWithNoEvaluatedRules() throws Exception {
        rulesEvaluated = 0;
        startServer();
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("INVALID_REPORT");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "[{\"severity\":\"CRITICAL\",\"count\":0},{\"severity\":\"HIGH\",\"count\":1},{\"severity\":\"MEDIUM\",\"count\":0},{\"severity\":\"LOW\",\"count\":0},{\"severity\":\"INFO\",\"count\":0}]",
                "[{\"severity\":\"CRITICAL\",\"count\":0},{\"severity\":\"HIGH\",\"count\":0},{\"severity\":\"MEDIUM\",\"count\":0},{\"severity\":\"LOW\",\"count\":0},{\"severity\":\"LOW\",\"count\":0}]",
                "[{\"severity\":\"CRITICAL\",\"count\":0},{\"severity\":\"HIGH\",\"count\":0},{\"severity\":\"MEDIUM\",\"count\":0},{\"severity\":\"LOW\",\"count\":0},{\"severity\":\"UNKNOWN\",\"count\":0}]"
            })
    void rejectsMissingMalformedOrContradictorySeverityCounts(String counts) throws Exception {
        reportSeverityCounts = counts;
        startServer();
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("\"status\": \"NOT_ASSESSED\"");
    }

    @Test
    void rejectsIncorrectSeverityDistributionEvenWhenTheTotalMatches() throws Exception {
        finding = true;
        reportSeverityCounts = severityCounts(0, 1);
        startServer();
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("COUNTS_INCONSISTENT");
    }

    @Test
    void rejectsMoreViolatingRulesThanWereEvaluated() throws Exception {
        finding = true;
        findingRuleCount = 2;
        findingCount = 2;
        rulesEvaluated = 1;
        startServer();
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("COUNTS_INCONSISTENT");
    }

    @Test
    void rejectsAContradictoryScanSummary() throws Exception {
        mismatchScanSummary = true;
        startServer();
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("SCAN_REPORT_MISMATCH");
    }

    @Test
    void junitCountsEachFailingAdvisor() throws Exception {
        finding = true;
        springFails = false;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"high"},
                  "spring":{"required":true,"failOn":"high"}
                }}
                """);
        CiAssessmentRunner runner = runner(policy, temp.resolve("witness.json"), temp.resolve("out"));
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        assertThat(Files.readString(temp.resolve("out/junit.xml")))
                .contains("failures=\"2\"")
                .contains("<testcase classname=\"bootui.ci\" name=\"architecture\"><failure")
                .contains("<testcase classname=\"bootui.ci\" name=\"spring\"><failure");
    }

    @Test
    void failedReportWriteDoesNotLeavePassingReports() throws Exception {
        startServer();
        Path output = temp.resolve("out");
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), output);
        assertThat(runner.attach()).isZero();
        obstructOutput = output;
        assertThatThrownBy(runner::assess).hasMessageContaining("Could not write complete");
        assertThat(output.resolve("bundle.json")).doesNotExist();
        assertThat(output.resolve("summary.md")).doesNotExist();
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
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"memory":{"required":true,"failOn":"high"}}}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported advisor");
        assertThat(CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"database-advisor":{"required":true,"failOn":"high"}}}
                        """, "0".repeat(64)).advisors()).containsKey(CiPolicy.Advisor.DATABASE);
    }

    @Test
    void recordsAUsefulSanitizedCodeWhenAttachPolicyIsInvalid() throws Exception {
        Path policy = temp.resolve("invalid-policy.json");
        Files.writeString(policy, "{\"schemaVersion\":1,\"advisors\":{}}");
        Path witness = temp.resolve("invalid-policy-witness.json");
        CiAssessmentRunner runner = new CiAssessmentRunner(
                "http://127.0.0.1:8080", "/bootui/api", 5, "invalid-policy", policy, witness, null);

        assertThat(runner.attach()).isEqualTo(1);
        assertThat(Files.readString(witness)).contains("\"code\": \"INVALID_POLICY\"");
        CiAssessmentRunner assess = new CiAssessmentRunner(
                "http://127.0.0.1:8080", "/bootui/api", 5, "invalid-policy", policy, witness, temp.resolve("out"));
        assertThat(assess.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("out/bundle.json"))).contains("\"code\": \"INVALID_POLICY\"");
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
            if (obstructOutput != null && ++overviewRequests == 2) {
                Files.createDirectory(obstructOutput.resolve("junit.xml"));
            }
            body = overview();
        } else if (path.endsWith("/tools/architecture_scan")
                || path.endsWith("/tools/database_advisor_scan")
                || path.endsWith("/tools/spring_scan") && !springFails) {
            boolean database = path.endsWith("/tools/database_advisor_scan");
            String subject = database
                    ? "tablesAnalyzed"
                    : path.endsWith("/tools/spring_scan") ? "componentsAnalyzed" : "classesAnalyzed";
            int subjects = database ? 1 : 3;
            int violations = !database && finding ? findingRuleCount : 0;
            int occurrences = !database && finding ? findingCount : 0;
            body = """
                    {"reportTool":"get_architecture_report",
                     "scan":{"status":"SCANNED","rulesEvaluated":%d,"%s":%d,"violationsFound":%d},
                     "findingsFound":%d,"severityCounts":%s,
                     "evidence":{"usable":true,"coverageComplete":true,"limitations":[]},
                     "violationDetails":{"scanId":"scan-12345678"}}
                    """.formatted(
                            database ? 5 : rulesEvaluated,
                            subject,
                            subjects,
                            violations,
                            mismatchScanSummary ? violations + 1 : violations,
                            severityCounts(occurrences, 0));
        } else if (path.endsWith("/tools/get_architecture_report")) {
            body = report();
        } else if (path.endsWith("/tools/get_spring_report")) {
            body = report().replace("\"classesAnalyzed\":3", "\"componentsAnalyzed\":3");
        } else if (path.endsWith("/tools/get_database_advisor_report")) {
            body = """
                    {"tablesAnalyzed":1,"rulesEvaluated":5,"violationsFound":0,"rulesSkipped":%d,"rulesErrored":%d,
                     "truncated":false,"diagnostics":[{"level":"%s"}],
                     "scan":{"status":"SCANNED","rulesEvaluated":5,"tablesAnalyzed":1,"violationsFound":0},"results":[],
                     "severityCounts":%s,
                     "evidence":{"usable":true,"coverageComplete":true,"limitations":[]},
                     "violationDetails":{"scanId":"scan-12345678","total":0,"retained":0,"truncated":false}}
                    """.formatted(
                            databaseRulesSkipped, databaseRulesErrored, databaseDiagnosticLevel, severityCounts(0, 0));
        } else if (path.endsWith("/tools/spring_scan")) {
            body = "{}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length());
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        } else if (path.endsWith("/tools/get_architecture_rule_violations")
                || path.endsWith("/tools/get_spring_rule_violations")) {
            JsonValue arguments =
                    JsonValue.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int offset = (int) arguments.get("offset").asLong(0);
            int limit = (int) arguments.get("limit").asLong(200);
            body = detailPage(offset, limit);
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
        tools.add(tool("database_advisor_scan", true, "NONE", List.of()));
        tools.add(tool("get_database_advisor_report", false, "NONE", List.of()));
        tools.add(tool(
                "get_database_advisor_rule_violations",
                false,
                "RULE_VIOLATIONS",
                List.of("id", "scanId", "offset", "limit")));
        return """
                {"serverName":"bootui","serverVersion":"dev","enabled":true,"maxResults":200,"tools":[%s]}
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
        String results = finding
                ? java.util.stream.IntStream.range(0, findingRuleCount)
                        .mapToObj(index -> """
                                {"id":"ARCH-%03d","severity":"HIGH","status":"VIOLATION","violationCount":%d,
                                 "dismissed":false,"suppressedCount":0}
                                """.formatted(index + 1, findingCount / findingRuleCount))
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                : "[]";
        return """
                {"classesAnalyzed":3,"rulesEvaluated":%d,"violationsFound":%d,
                 "scan":{"status":"SCANNED","rulesEvaluated":%d,"classesAnalyzed":3,"violationsFound":%d},
                 "results":%s,"analysisErrors":[],"severityCounts":%s,
                 "evidence":{"usable":true,"coverageComplete":true,"limitations":[]},
                 "violationDetails":{"scanId":"scan-12345678","total":%s,"retained":%s,"truncated":false}}
                """.formatted(
                rulesEvaluated,
                finding ? findingRuleCount : 0,
                rulesEvaluated,
                finding ? findingRuleCount : 0,
                results,
                reportSeverityCounts != null ? reportSeverityCounts : severityCounts(finding ? findingCount : 0, 0),
                finding ? findingCount : 0,
                finding ? findingCount : 0);
    }

    private static String severityCounts(int high, int medium) {
        return """
                [{"severity":"CRITICAL","count":0},{"severity":"HIGH","count":%d},
                 {"severity":"MEDIUM","count":%d},{"severity":"LOW","count":0},{"severity":"INFO","count":0}]
                """.formatted(high, medium);
    }

    private String detailPage(int offset, int limit) {
        detailRequests++;
        int returned = Math.min(limit, findingCount - offset);
        int reportedTotal = mismatchSecondPage && offset > 0 ? findingCount + 1 : findingCount;
        return """
                {"scanId":"scan-12345678","ruleId":"ARCH-001","violationCount":%d,"retainedCount":%d,
                 "truncated":false,"violations":[%s],
                 "page":{"total":%d,"matched":%d,"offset":%d,"limit":%d,"returned":%d,"hasMore":%s},
                 "identities":[],"suppressed":[],"suppressedCount":0}
                """.formatted(
                        reportedTotal,
                        reportedTotal,
                        java.util.Collections.nCopies(returned, "\"private-finding-payload\"").stream()
                                .collect(java.util.stream.Collectors.joining(",")),
                        reportedTotal,
                        reportedTotal,
                        offset,
                        limit,
                        returned,
                        offset + returned < reportedTotal);
    }
}
