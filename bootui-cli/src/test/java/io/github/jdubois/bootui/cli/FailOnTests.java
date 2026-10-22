package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code --fail-on} severity gate: which commands have it, and what each answer makes it exit with. */
class FailOnTests {

    private static final String CATALOG = "{\"enabled\":true,\"serverName\":\"bootui\",\"serverVersion\":\"1.20.0\","
            + "\"endpoint\":\"/bootui/api/cli\",\"maxResults\":200,\"callCount\":0,\"totalLatencyMillis\":0,"
            + "\"capacityRefusals\":0,\"timeouts\":0,\"toolCount\":0,\"tools\":[]}";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;
    private String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // A refusal makes the CLI confirm BootUI answered, by reading the catalog.
            boolean catalogRead = path.endsWith("/cli");
            byte[] body = (catalogRead ? CATALOG : responseBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(catalogRead ? 200 : status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String summary(String scanStatus, String... counts) {
        return "{\"reportTool\":\"get_hibernate_report\",\"scan\":{\"status\":\"" + scanStatus + "\"},"
                + "\"findingsFound\":0,\"severityCounts\":[" + String.join(",", counts) + "]}";
    }

    private static String count(String severity, long count) {
        return "{\"severity\":\"" + severity + "\",\"count\":" + count + "}";
    }

    @Test
    void aFindingAtTheThresholdExitsThreeAndPrintsTheSameJson() {
        responseBody = summary("SCANNED", count("HIGH", 2), count("LOW", 1));
        Result gated = run("hibernate", "scan", "--fail-on", "high", "--json");
        Result plain = run("hibernate", "scan", "--json");

        assertThat(gated.exitCode).isEqualTo(ExitCodes.THRESHOLD).isEqualTo(3);
        assertThat(gated.err).contains("--fail-on high: found HIGH=2.").doesNotContain("LOW");
        assertThat(gated.out).isEqualTo(plain.out).isEqualTo(responseBody + System.lineSeparator());
        assertThat(plain.exitCode).isZero();
    }

    @Test
    void aFindingAboveTheThresholdExitsThree() {
        responseBody = summary("SCANNED", count("CRITICAL", 1));

        assertThat(run("hibernate", "scan", "--fail-on", "high").exitCode).isEqualTo(ExitCodes.THRESHOLD);
    }

    @Test
    void onlyLowerSeveritiesPass() {
        responseBody = summary("SCANNED", count("CRITICAL", 0), count("HIGH", 0), count("MEDIUM", 4), count("LOW", 9));

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isZero();
        assertThat(result.err).isEmpty();
    }

    @Test
    void theThresholdIsInclusiveAtEveryLevel() {
        for (String level : List.of("critical", "high", "medium", "low", "info")) {
            responseBody = summary("SCANNED", count(level.toUpperCase(), 1));
            assertThat(run("hibernate", "scan", "--fail-on", level).exitCode)
                    .as(level)
                    .isEqualTo(ExitCodes.THRESHOLD);
        }
    }

    @Test
    void theValueIsCaseInsensitive() {
        responseBody = summary("SCANNED", count("HIGH", 1));

        assertThat(run("hibernate", "scan", "--fail-on", "HiGh").exitCode).isEqualTo(ExitCodes.THRESHOLD);
    }

    @Test
    void aReportWithNoFindingsPasses() {
        responseBody = summary("SCANNED", count("CRITICAL", 0), count("HIGH", 0));

        assertThat(run("hibernate", "scan", "--fail-on", "low").exitCode).isZero();
    }

    @Test
    void severitiesOutsideTheOrderAreIgnored() {
        responseBody = summary("SCANNED", count("UNKNOWN", 3), count("NONE", 5));

        assertThat(run("vulnerabilities", "scan", "--fail-on", "info").exitCode).isZero();
    }

    @Test
    void aPartialScanWithAFindingStillFails() {
        responseBody = summary("PARTIAL", count("HIGH", 1));

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.THRESHOLD);
        assertThat(result.err).doesNotContain("PARTIAL");
    }

    @Test
    void aPartialScanWithoutAFindingPassesButSaysItIsNotProof() {
        // The Hibernate advisor is PARTIAL on a real application, so refusing it would make the gate unusable.
        responseBody = summary("PARTIAL", count("HIGH", 0), count("MEDIUM", 3));

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isZero();
        assertThat(result.err).contains("PARTIAL").contains("not proof");
    }

    @Test
    void anIgnoredSeverityWithFindingsIsNamedButDoesNotGate() {
        responseBody = summary("SCANNED", count("HIGH", 0), count("UNKNOWN", 3), count("NONE", 0));

        Result result = run("vulnerabilities", "scan", "--fail-on", "low");

        assertThat(result.exitCode).isZero();
        assertThat(result.err).contains("does not count UNKNOWN=3").doesNotContain("NONE");
    }

    @Test
    void zeroFindingsFromAScanWithNoUsableEvidenceCannotPass() {
        responseBody = "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":[" + count("HIGH", 0)
                + "],\"evidence\":{\"usable\":false,\"coverageComplete\":false,\"limitations\":[]}}";

        Result result = run("architecture", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("cannot evaluate --fail-on").contains("no usable evidence");
    }

    @Test
    void aFindingStillFailsWhenTheEvidenceIsNotUsableAndUsableEvidencePasses() {
        responseBody = "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":[" + count("HIGH", 1)
                + "],\"evidence\":{\"usable\":false}}";
        assertThat(run("architecture", "scan", "--fail-on", "high").exitCode).isEqualTo(ExitCodes.THRESHOLD);

        responseBody = "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":[" + count("HIGH", 0)
                + "],\"evidence\":{\"usable\":true}}";
        assertThat(run("architecture", "scan", "--fail-on", "high").exitCode).isZero();
    }

    @Test
    void payloadTextIsNeverEchoedToStderrVerbatim() {
        responseBody = "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":[{\"severity\":\"HIGH\\u001b[31m\","
                + "\"count\":0},{\"severity\":\"WEIRD\\u001b[31m\",\"count\":2},{\"severity\":\"HIGH\\u001b\","
                + "\"count\":1}]}";
        Result ignored = run("hibernate", "scan", "--fail-on", "high");
        assertThat(ignored.err).doesNotContain("\u001b");

        responseBody = summary("EVIL\\u001b[31m", count("HIGH", 0));
        Result status = run("hibernate", "scan", "--fail-on", "high");
        assertThat(status.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(status.err).doesNotContain("\u001b").contains("not recognised");
    }

    @Test
    void aFullReportShapeGatesTooAndKeepsItsOtherFields() {
        responseBody = "{\"scanningEnabled\":true,\"total\":4,\"vulnerable\":1,"
                + "\"severityCounts\":[" + count("CRITICAL", 1) + "," + count("HIGH", 0) + "],"
                + "\"scan\":{\"scanner\":\"OSV.dev\",\"status\":\"SCANNED\",\"packagesScanned\":4},"
                + "\"dependencies\":[]}";

        Result result = run("vulnerabilities", "report", "--fail-on", "high", "--json");

        assertThat(result.exitCode).isEqualTo(ExitCodes.THRESHOLD);
        assertThat(result.out).isEqualTo(responseBody + System.lineSeparator());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_SCANNED", "DISABLED", "ERROR", "FAILED", "SOMETHING_NEW"})
    void aScanThatDidNotEvaluateIsNeverAPass(String scanStatus) {
        responseBody = summary(scanStatus, count("CRITICAL", 0), count("HIGH", 0));

        Result result = run("hibernate", "report", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("cannot evaluate --fail-on").contains(scanStatus);
        assertThat(result.out).contains("severityCounts");
    }

    @Test
    void aMissingScanStatusIsUnevaluable() {
        responseBody = "{\"severityCounts\":[" + count("HIGH", 0) + "]}";

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("cannot evaluate --fail-on").contains("scan status");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"scan\":{\"status\":\"SCANNED\"}}",
                "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":null}",
                "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":[]}",
                "{\"scan\":{\"status\":\"SCANNED\"},\"severityCounts\":{\"HIGH\":0}}",
                "[]",
                "null"
            })
    void aMissingOrMisshapenSeverityCountsIsUnevaluable(String body) {
        responseBody = body;

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("cannot evaluate --fail-on");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"count\":1}",
                "{\"severity\":5,\"count\":1}",
                "{\"severity\":\"HIGH\"}",
                "{\"severity\":\"HIGH\",\"count\":\"1\"}",
                "{\"severity\":\"HIGH\",\"count\":-1}",
                "{\"severity\":\"HIGH\",\"count\":1.5}",
                "{\"severity\":\"HIGH\",\"count\":1e2}",
                "\"HIGH\""
            })
    void aMalformedEntryIsUnevaluableEvenAfterAnEarlierMatch(String entry) {
        responseBody = summary("SCANNED", count("CRITICAL", 1), entry);

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("cannot evaluate --fail-on");
    }

    @Test
    void anInvalidValueIsAUsageErrorListingTheAcceptedOnesAndMakesNoCall() {
        Result result = run("hibernate", "scan", "--fail-on", "severe");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).contains("severe").contains("critical, high, medium, low, info");
        assertThat(paths).isEmpty();
    }

    @Test
    void theOptionNeedsAValue() {
        Result result = run("hibernate", "scan", "--fail-on");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(paths).isEmpty();
    }

    @Test
    void theThresholdIsNeverSentToTheApplication() {
        responseBody = summary("SCANNED", count("HIGH", 0));

        run("hibernate", "scan", "--fail-on", "high");
        run("vulnerabilities", "report", "--fail-on", "high", "--query", "x", "--limit", "3");

        assertThat(bodies).containsExactly("{}", "{\"query\":\"x\",\"limit\":3}");
    }

    @Test
    void aRefusedCallKeepsExitCodeTwo() {
        status = 403;
        responseBody = "{\"error\":\"Panel 'hibernate' is disabled\"}";

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.REFUSED);
        assertThat(result.out).isEmpty();
    }

    @Test
    void aFailedCallKeepsItsExitCodeAndPrintsNothingToGate() {
        status = 500;
        responseBody = "{\"error\":\"boom\"}";

        Result result = run("hibernate", "scan", "--fail-on", "high");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err).doesNotContain("--fail-on");
        assertThat(result.out).isEmpty();
    }

    @Test
    void thePayloadIsPrintedBeforeTheGateIsEvaluated() {
        responseBody = summary("NOT_SCANNED", count("HIGH", 0));

        Result result = run("hibernate", "report", "--fail-on", "high", "--json");

        assertThat(result.exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(result.out).isEqualTo(responseBody + System.lineSeparator());
    }

    @Test
    void everyAdvisorToolTakesTheOptionAndNoOtherCommandDoes() {
        Set<String> accepting = new TreeSet<>();
        Set<String> rejecting = new TreeSet<>();
        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            List<String> args = new ArrayList<>(tool.path());
            if (tool.takesId()) {
                args.add("some-id");
            }
            if (tool.takesScanId()) {
                args.addAll(List.of("--scan-id", "scan-1"));
            }
            args.addAll(List.of("--fail-on", "high"));
            responseBody = summary("SCANNED", count("HIGH", 0));
            Result result = run(args.toArray(new String[0]));
            (result.exitCode == ExitCodes.SUCCESS ? accepting : rejecting).add(tool.name());
        }

        assertThat(accepting).containsExactlyInAnyOrderElementsOf(FailOnTools.TOOLS);
        assertThat(rejecting).containsExactlyInAnyOrderElementsOf(nonAdvisorTools());
    }

    @Test
    void everyScanAndReportToolIsEitherGatedOrExplicitlyExcluded() {
        Set<String> candidates = new TreeSet<>();
        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            if (tool.name().endsWith("_scan")
                    || (tool.name().startsWith("get_") && tool.name().endsWith("_report"))) {
                candidates.add(tool.name());
            }
        }

        Set<String> known = new HashSet<>(FailOnTools.TOOLS);
        known.addAll(FailOnTools.EXCLUDED);
        assertThat(candidates)
                .as("add a new advisor to FailOnTools.TOOLS, or to FailOnTools.EXCLUDED with a reason")
                .isSubsetOf(known);
        assertThat(FailOnTools.TOOLS).doesNotContainAnyElementsOf(FailOnTools.EXCLUDED);
        assertThat(known)
                .as("a gated or excluded tool that no longer exists is stale")
                .isSubsetOf(ToolManifest.bundled().tools().stream()
                        .map(ToolManifest.Tool::name)
                        .toList());
    }

    private static Set<String> nonAdvisorTools() {
        Set<String> others = new HashSet<>();
        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            if (!FailOnTools.TOOLS.contains(tool.name())) {
                others.add(tool.name());
            }
        }
        return others;
    }

    private Result run(String... command) {
        List<String> args = new ArrayList<>();
        args.add("--url");
        args.add("http://127.0.0.1:" + server.getAddress().getPort());
        args.addAll(List.of(command));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = BootUiCli.run(
                args.toArray(new String[0]), Map.of(), false, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Result(exitCode, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {}
}
