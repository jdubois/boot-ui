package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.cli.BootUiCli;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private boolean springFindingsMedium;
    private boolean architectureFindingsMedium;
    private boolean reverseFindingOrder;
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
    private String frameworkName = "Spring Boot";
    private boolean changeFrameworkBeforeFinalIdentity;
    private String currentInstance = "a1b2c3d4";
    private boolean runtimeInsightsAdvertised = true;
    private boolean runtimeInsightsPanelEnabled = true;
    private boolean changeIdentityAfterRuntimeInsights;
    private int runtimeInsightsRequests;
    private int runtimeInsightsStatus = 200;
    private long runtimeInsightsDelayMillis;
    private String runtimeInsightsQuery;
    private int runtimeInsightsLimit;
    private String runtimeInsightsBody = runtimeInsightsReport(false, false);

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
        assertThat(runtimeInsightsRequests).isZero();
        assertThat(Files.readString(output.resolve("summary.md"))).doesNotContain("Runtime Insights context");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
        assertThat(Files.readString(output.resolve("junit.xml"))).contains("<testsuite");
    }

    @Test
    void assessesAQuarkusTargetAndRecordsFrameworkIdentity() throws Exception {
        frameworkName = "Quarkus";
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("quarkus-witness.json");
        Path output = temp.resolve("quarkus-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(Files.readString(witness)).contains("\"schemaVersion\": 2", "\"frameworkName\": \"Quarkus\"");
        assertThat(runner.assess()).isZero();

        String bundle = Files.readString(output.resolve("bundle.json"));
        assertThat(bundle).contains("\"schemaVersion\": 2", "\"frameworkName\": \"Quarkus\"");
        assertThat(Files.readString(output.resolve("summary.md"))).contains("Quarkus");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
    }

    @Test
    void showsQuarkusThresholdFindingsInTheSummary() throws Exception {
        frameworkName = "Quarkus";
        finding = true;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("quarkus-witness.json");
        Path output = temp.resolve("quarkus-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("Quarkus", "| architecture | ARCH-001 | HIGH | 1 | yes |")
                .doesNotContain("private-finding-payload");
    }

    @Test
    void rejectsTheQuarkusApplicationAdvisorBeforeTheWorkload() throws Exception {
        frameworkName = "Quarkus";
        startServer();
        Path policy = writePolicyWithOptionalSpringAdvisor();
        Path witness = temp.resolve("unsupported-witness.json");
        Path output = temp.resolve("unsupported-assessment");
        StringWriter cliOutput = new StringWriter();

        int exitCode = BootUiCli.run(
                new String[] {
                    "ci",
                    "attach",
                    "--url",
                    url(),
                    "--assessment-id",
                    "test-assessment",
                    "--policy",
                    policy.toString(),
                    "--output",
                    witness.toString()
                },
                Map.of(),
                false,
                new PrintWriter(cliOutput, true),
                new PrintWriter(cliOutput, true));

        assertThat(exitCode).isEqualTo(1);
        assertThat(cliOutput.toString()).contains("UNSUPPORTED_ADVISOR_FOR_FRAMEWORK");
        assertThat(Files.readString(witness)).contains("UNSUPPORTED_ADVISOR_FOR_FRAMEWORK");
        assertThat(overviewRequests).isEqualTo(1);
        assertThat(detailRequests).isZero();

        assertThat(runner(policy, witness, output).assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("UNSUPPORTED_ADVISOR_FOR_FRAMEWORK");
        assertThat(overviewRequests).isEqualTo(1);
        assertThat(detailRequests).isZero();
    }

    @Test
    void rejectsAQuarkusWitnessWithASpringPolicyBeforeContactingTheTarget() throws Exception {
        startServer();
        Path policy = writePolicyWithSpringAdvisor();
        Path witness = temp.resolve("framework-mismatch-witness.json");
        Path output = temp.resolve("framework-mismatch-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        JsonValue attached = JsonValue.parse(Files.readString(witness));
        Map<String, JsonValue> altered = new LinkedHashMap<>();
        for (String key : attached.names()) {
            altered.put(key, "frameworkName".equals(key) ? JsonValue.of("Quarkus") : attached.get(key));
        }
        Files.writeString(witness, JsonWriter.pretty(JsonValue.object(altered)));
        overviewRequests = 0;

        assertThat(runner.assess()).isEqualTo(1);
        assertThat(overviewRequests).isZero();
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("WITNESS_MISMATCH");
    }

    @Test
    void keepsLegacySpringWitnessesValid() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("legacy-witness.json");
        Path output = temp.resolve("legacy-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        Files.writeString(witness, asLegacyWitness(Files.readString(witness)));
        assertThat(runner.assess()).isZero();
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"schemaVersion\": 2", "\"frameworkName\": \"Spring Boot\"");
    }

    @Test
    void rejectsAQuarkusWitnessDowngradedToLegacySchemaBeforeScanning() throws Exception {
        frameworkName = "Quarkus";
        finding = true;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("legacy-quarkus-witness.json");
        Path output = temp.resolve("legacy-quarkus-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        Files.writeString(witness, asLegacyWitness(Files.readString(witness)));
        overviewRequests = 0;

        assertThat(runner.assess()).isEqualTo(1);
        assertThat(overviewRequests).isEqualTo(1);
        assertThat(detailRequests).isZero();
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("IDENTITY_CHANGED");
    }

    @Test
    void rejectsAnUnsupportedFramework() throws Exception {
        frameworkName = "Micronaut";
        startServer();
        Path witness = temp.resolve("unsupported-framework.json");

        assertThat(runner(writePolicy(), witness, temp.resolve("unused")).attach())
                .isEqualTo(1);
        assertThat(Files.readString(witness)).contains("UNSUPPORTED_FRAMEWORK");
    }

    @Test
    void rejectsFrameworkIdentityChangesBeforeAndAfterCollection() throws Exception {
        frameworkName = "Quarkus";
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("framework-witness.json");
        CiAssessmentRunner attached = runner(policy, witness, temp.resolve("before-change"));
        assertThat(attached.attach()).isZero();

        frameworkName = "Spring Boot";
        assertThat(attached.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("before-change/bundle.json"))).contains("IDENTITY_CHANGED");

        frameworkName = "Quarkus";
        overviewRequests = 0;
        Files.delete(witness);
        CiAssessmentRunner afterCollection = runner(policy, witness, temp.resolve("after-change"));
        assertThat(afterCollection.attach()).isZero();
        changeFrameworkBeforeFinalIdentity = true;
        assertThat(afterCollection.assess()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("after-change/bundle.json")))
                .contains("\"frameworkName\": \"Quarkus\"", "IDENTITY_CHANGED");
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
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("| architecture | ARCH-001 | HIGH | 1 | yes |");
    }

    @Test
    void showsThresholdFindingsBeforeNonThresholdFindings() throws Exception {
        finding = true;
        springFails = false;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"critical"},
                  "spring":{"required":true,"failOn":"high"}
                }}
                """);
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        String summary = Files.readString(output.resolve("summary.md"));
        String threshold = "| spring | ARCH-001 | HIGH | 1 | yes |";
        String nonThreshold = "| architecture | ARCH-001 | HIGH | 1 | no |";
        assertThat(summary).contains(threshold, nonThreshold);
        assertThat(summary.indexOf(threshold)).isLessThan(summary.indexOf(nonThreshold));
    }

    @Test
    void boundsSummaryFindingRowsWithoutTruncatingTheBundle() throws Exception {
        finding = true;
        findingRuleCount = 55;
        findingCount = 55;
        rulesEvaluated = 55;
        startServer();
        Path policy = writePolicy();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        String summary = Files.readString(output.resolve("summary.md"));
        assertThat(summary)
                .contains("| architecture | ARCH-001 | HIGH | 1 | yes |")
                .contains("| architecture | ARCH-050 | HIGH | 1 | yes |")
                .contains(
                        "Showing 50 of 55 rule summaries (5 omitted); see `bundle.json` for the complete sanitized list.")
                .doesNotContain("ARCH-051")
                .doesNotContain("private-finding-payload");
        assertThat(summary.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(CiAssessmentRunner.MAX_SUMMARY_BYTES);
        JsonValue bundle = JsonValue.parse(Files.readString(output.resolve("bundle.json")));
        assertThat(bundle.get("advisors").get(0).get("findings").size()).isEqualTo(55);
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(3);
    }

    @Test
    void prioritizesThresholdFindingsBeforeApplyingTheSummaryLimit() throws Exception {
        finding = true;
        findingRuleCount = 51;
        findingCount = 51;
        rulesEvaluated = 51;
        springFails = false;
        springFindingsMedium = true;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"critical"},
                  "spring":{"required":true,"failOn":"medium"}
                }}
                """);
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        String summary = Files.readString(output.resolve("summary.md"));
        List<String> rows =
                summary.lines().filter(line -> line.contains("| ARCH-")).toList();
        List<String> expected = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(index -> "| spring | ARCH-%03d | MEDIUM | 1 | yes |".formatted(index))
                .toList();
        assertThat(rows).containsExactlyElementsOf(expected);
        assertThat(summary).contains("Showing 50 of 102 rule summaries (52 omitted)");
        JsonValue advisors =
                JsonValue.parse(Files.readString(output.resolve("bundle.json"))).get("advisors");
        assertThat(advisors.get(0).get("findings").size()).isEqualTo(51);
        assertThat(advisors.get(1).get("findings").size()).isEqualTo(51);
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(3);
    }

    @Test
    void sortsThresholdFindingsBySeverityAdvisorAndRuleId() throws Exception {
        finding = true;
        findingRuleCount = 2;
        findingCount = 2;
        rulesEvaluated = 2;
        springFails = false;
        architectureFindingsMedium = true;
        reverseFindingOrder = true;
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"medium"},
                  "spring":{"required":true,"failOn":"medium"}
                }}
                """);
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("severity-order");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(3);
        assertThat(Files.readString(output.resolve("summary.md"))
                        .lines()
                        .filter(line -> line.contains("| ARCH-"))
                        .toList())
                .containsExactly(
                        "| spring | ARCH-001 | HIGH | 1 | yes |",
                        "| spring | ARCH-002 | HIGH | 1 | yes |",
                        "| architecture | ARCH-001 | MEDIUM | 1 | yes |",
                        "| architecture | ARCH-002 | MEDIUM | 1 | yes |");

        architectureFindingsMedium = false;
        Path equalSeverityOutput = temp.resolve("advisor-order");
        CiAssessmentRunner equalSeverityRunner =
                runner(policy, temp.resolve("advisor-witness.json"), equalSeverityOutput);
        assertThat(equalSeverityRunner.attach()).isZero();
        assertThat(equalSeverityRunner.assess()).isEqualTo(3);
        assertThat(Files.readString(equalSeverityOutput.resolve("summary.md"))
                        .lines()
                        .filter(line -> line.contains("| ARCH-"))
                        .toList())
                .containsExactly(
                        "| architecture | ARCH-001 | HIGH | 1 | yes |",
                        "| architecture | ARCH-002 | HIGH | 1 | yes |",
                        "| spring | ARCH-001 | HIGH | 1 | yes |",
                        "| spring | ARCH-002 | HIGH | 1 | yes |");
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
        assertThat(Files.readString(output.resolve("summary.md"))).contains("Assessment errors: `IDENTITY_CHANGED`.");
    }

    @Test
    void marksRequestedRuntimeContextNotCollectedWhenTheWitnessCannotBeRead() throws Exception {
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("missing-witness.json");
        Path output = temp.resolve("assessment");

        assertThat(runner(policy, witness, output).assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"code\": \"ASSESSMENT_IO_FAILED\"", "\"status\": \"NOT_COLLECTED\"");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("Status: `NOT_COLLECTED`")
                .doesNotContain("Retained events");
    }

    @Test
    void omitsRuntimeContextSummaryWhenThePolicyCouldNotBeRead() throws Exception {
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        assertThat(runner(policy, witness, temp.resolve("attached")).attach()).isZero();
        Files.delete(policy);
        Path output = temp.resolve("missing-policy");

        assertThat(runner(policy, witness, output).assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("summary.md")))
                .doesNotContain("Runtime Insights context")
                .doesNotContain("Retained events");
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
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("| architecture | ARCH-001 | HIGH | 1 | yes |", "Assessment errors: `SCAN_FAILED`.");
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
        assertThat(CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}}}
                        """, "0".repeat(64)).includeInsights()).isFalse();
        assertThat(CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                         "runtimeContext":{"includeInsights":true}}
                        """, "0".repeat(64)).includeInsights()).isTrue();
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                         "runtimeContext":{}}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing runtimeContext key");
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                         "runtimeContext":{"includeInsights":"yes"}}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a boolean");
        assertThatThrownBy(() -> CiPolicy.parse("""
                        {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                         "runtimeContext":{"includeInsights":true,"other":true}}
                        """, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown runtimeContext key");
    }

    @Test
    void capturesOnlyBoundedSanitizedRuntimeContextAndKeepsItOutOfJUnit() throws Exception {
        startServer();
        runtimeInsightsBody = runtimeInsightsReport(false, false);
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();

        String bundle = Files.readString(output.resolve("bundle.json"));
        String summary = Files.readString(output.resolve("summary.md"));
        String junit = Files.readString(output.resolve("junit.xml"));
        assertThat(runtimeInsightsRequests).isEqualTo(1);
        assertThat(runtimeInsightsQuery).isEmpty();
        assertThat(runtimeInsightsLimit).isEqualTo(20);
        assertThat(bundle)
                .contains("\"status\": \"PASS\"")
                .contains("\"status\": \"CAPTURED\"")
                .contains("\"workloadAttribution\": \"UNVERIFIED\"")
                .contains("\"byRequestId\": 1")
                .contains("\"byExecutionId\": 1")
                .contains("\"byTraceId\": 1")
                .contains("\"unlinked\": 1")
                .contains("\"droppedEvents\": 2")
                .contains("\"affected\": 5");
        assertThat(summary)
                .contains("Runtime Insights context")
                .contains("does not prove that the CI workload exercised this process")
                .contains("repeated-selects");
        for (String privateValue : List.of(
                "private-id",
                "private-route",
                "private sentence",
                "private-request",
                "private-verify",
                "private-next")) {
            assertThat(bundle).doesNotContain(privateValue);
            assertThat(summary).doesNotContain(privateValue);
        }
        assertThat(junit).contains("tests=\"1\"").doesNotContain("Runtime Insights");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
    }

    @Test
    void unavailableRuntimeInsightsDoesNotChangeTheAdvisorVerdict() throws Exception {
        runtimeInsightsAdvertised = false;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(runtimeInsightsRequests).isZero();
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"")
                .contains("\"status\": \"UNAVAILABLE\"");
        assertThat(Files.readString(output.resolve("summary.md"))).doesNotContain("Retained events");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();

        runtimeInsightsAdvertised = true;
        runtimeInsightsPanelEnabled = false;
        Path disabledOutput = temp.resolve("disabled-context");
        CiAssessmentRunner disabled = runner(policy, witness, disabledOutput);
        assertThat(disabled.assess()).isZero();
        assertThat(runtimeInsightsRequests).isZero();
        assertThat(Files.readString(disabledOutput.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"UNAVAILABLE\"");
        assertThat(Files.readString(disabledOutput.resolve("summary.md"))).doesNotContain("Retained events");
    }

    @Test
    void runtimeContextRequestFailureDoesNotChangeTheAdvisorVerdict() throws Exception {
        runtimeInsightsStatus = 503;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("failed-context");
        CiAssessmentRunner failedContext = runner(policy, witness, output);

        assertThat(failedContext.attach()).isZero();
        assertThat(failedContext.assess()).isZero();
        assertThat(runtimeInsightsRequests).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"")
                .contains("\"status\": \"ERROR\"");
        assertThat(Files.readString(output.resolve("summary.md"))).doesNotContain("Retained events");
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void runtimeInsightsRefusalOrMissingToolIsUnavailableAndReportOnly(int status) throws Exception {
        runtimeInsightsStatus = status;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("unavailable-context");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(runtimeInsightsRequests).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"UNAVAILABLE\"");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("No Runtime Insights data was captured")
                .doesNotContain("Retained events");
    }

    @Test
    void unavailableInsightsReportDoesNotExposeItsReasonOrChangeTheVerdict() throws Exception {
        runtimeInsightsBody = """
                {"available":false,"unavailableReason":"private unavailable reason"}
                """;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("unavailable-report");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"UNAVAILABLE\"")
                .doesNotContain("private unavailable reason");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("No Runtime Insights data was captured")
                .doesNotContain("private unavailable reason", "Retained events");
    }

    @Test
    void runtimeInsightsNeverChangesAdvisorThresholdOrJUnit() throws Exception {
        finding = true;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        CiAssessmentRunner captured = runner(policy, witness, temp.resolve("captured-violation"));

        assertThat(captured.attach()).isZero();
        assertThat(captured.assess()).isEqualTo(3);
        assertThat(Files.readString(temp.resolve("captured-violation/bundle.json")))
                .contains("\"status\": \"VIOLATION\"", "\"status\": \"CAPTURED\"");
        assertThat(Files.readString(temp.resolve("captured-violation/junit.xml")))
                .contains("<failure");

        runtimeInsightsStatus = 503;
        CiAssessmentRunner unavailable = runner(policy, witness, temp.resolve("error-violation"));
        assertThat(unavailable.assess()).isEqualTo(3);
        assertThat(Files.readString(temp.resolve("error-violation/bundle.json")))
                .contains("\"status\": \"VIOLATION\"", "\"status\": \"ERROR\"");
        assertThat(Files.readString(temp.resolve("error-violation/junit.xml")))
                .contains("<failure")
                .doesNotContain("<error");
    }

    @Test
    void invalidRuntimeKindIsDroppedAndDoesNotChangeTheAdvisorVerdict() throws Exception {
        runtimeInsightsBody = runtimeInsightsReport(false, false)
                .replace("\"kind\":\"repeated-selects\"", "\"kind\":\"GET /private-route\"");
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("invalid-kind");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"ERROR\"")
                .doesNotContain("GET /private-route");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("No Runtime Insights data was captured")
                .doesNotContain("GET /private-route", "Retained events");
    }

    @Test
    void insufficientRemainingBudgetSkipsOptionalInsightsAndPreservesIdentityCheck() throws Exception {
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("high-timeout");
        CiAssessmentRunner runner =
                new CiAssessmentRunner(url(), "/bootui/api", 180, "test-assessment", policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(runtimeInsightsRequests).isZero();
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"ERROR\"");
        assertThat(Files.readString(output.resolve("junit.xml"))).doesNotContain("<error");
    }

    @Test
    void malformedRuntimeObservationStatusDoesNotChangeTheAdvisorVerdictOrSkipIdentityCheck() throws Exception {
        runtimeInsightsBody = runtimeInsightsReport(false, false).replace("\"status\":\"OBSERVED\"", "\"status\":null");
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("malformed-context");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(runtimeInsightsRequests).isEqualTo(1);
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"status\": \"ERROR\"");
        assertThat(Files.readString(output.resolve("junit.xml")))
                .contains("tests=\"1\"")
                .doesNotContain("<error");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
    }

    @Test
    void slowRuntimeContextRequestDoesNotPreventTheFinalIdentityCheck() throws Exception {
        runtimeInsightsDelayMillis = 1_200;
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("slow-context");
        CiAssessmentRunner runner =
                new CiAssessmentRunner(url(), "/bootui/api", 1, "test-assessment", policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(runtimeInsightsRequests).isEqualTo(1);
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"")
                .contains("\"status\": \"ERROR\"");
    }

    @Test
    void lowFootprintContextIsMarkedPartialAndIdentityIsRecheckedAfterCollection() throws Exception {
        runtimeInsightsBody = runtimeInsightsReport(true, true);
        startServer();
        Path policy = writePolicyWithRuntimeInsights();
        Path witness = temp.resolve("witness.json");
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PARTIAL\"")
                .contains("\"lowFootprintActive\": true")
                .contains("\"capturePaused\": true");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();

        changeIdentityAfterRuntimeInsights = true;
        currentInstance = "a1b2c3d4";
        Path changedOutput = temp.resolve("changed-identity");
        CiAssessmentRunner changed = runner(policy, witness, changedOutput);
        assertThat(changed.assess()).isEqualTo(1);
        assertThat(Files.readString(changedOutput.resolve("bundle.json")))
                .contains("\"status\": \"NOT_ASSESSED\"")
                .contains("IDENTITY_CHANGED");
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

    private Path writePolicyWithSpringAdvisor() throws IOException {
        Path policy = temp.resolve("spring-policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"spring":{"required":true,"failOn":"high"}}}
                """);
        return policy;
    }

    private Path writePolicyWithOptionalSpringAdvisor() throws IOException {
        Path policy = temp.resolve("optional-spring-policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "hibernate":{"required":true,"failOn":"high"},
                  "spring":{"required":false,"failOn":"high"}
                }}
                """);
        return policy;
    }

    private Path writePolicyWithRuntimeInsights() throws IOException {
        Path policy = temp.resolve("policy-with-runtime-insights.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                 "runtimeContext":{"includeInsights":true}}
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
            overviewRequests++;
            if (obstructOutput != null && overviewRequests == 2) {
                Files.createDirectory(obstructOutput.resolve("junit.xml"));
            }
            body = overview();
        } else if (path.endsWith("/tools/get_runtime_insights")) {
            runtimeInsightsRequests++;
            JsonValue arguments =
                    JsonValue.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            runtimeInsightsQuery = arguments.get("query").asString(null);
            runtimeInsightsLimit = arguments.get("limit").asInt(0);
            if (runtimeInsightsDelayMillis > 0) {
                try {
                    Thread.sleep(runtimeInsightsDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while simulating Runtime Insights", interrupted);
                }
            }
            body = runtimeInsightsBody;
            if (changeIdentityAfterRuntimeInsights) {
                currentInstance = "b1b2c3d4";
            }
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
            boolean medium = path.endsWith("/tools/spring_scan") && springFindingsMedium
                    || path.endsWith("/tools/architecture_scan") && architectureFindingsMedium;
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
                            severityCounts(medium ? 0 : occurrences, medium ? occurrences : 0));
        } else if (path.endsWith("/tools/get_architecture_report")) {
            body = report(architectureFindingsMedium);
        } else if (path.endsWith("/tools/get_spring_report")) {
            body = report(springFindingsMedium).replace("\"classesAnalyzed\":3", "\"componentsAnalyzed\":3");
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
            body = detailPage(arguments.get("id").asString(null), offset, limit);
        } else {
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(
                path.endsWith("/tools/get_runtime_insights") ? runtimeInsightsStatus : 200, bytes.length);
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
        if (runtimeInsightsAdvertised) {
            tools.add(tool(
                    "get_runtime_insights",
                    false,
                    "QUERY_LIMIT",
                    List.of("query", "limit"),
                    "runtime-insights",
                    runtimeInsightsPanelEnabled,
                    true));
        }
        return """
                {"serverName":"bootui","serverVersion":"dev","enabled":true,"maxResults":200,"tools":[%s]}
                """.formatted(String.join(",", tools));
    }

    private static String tool(String name, boolean action, String schema, List<String> arguments) {
        String panel = name.startsWith("spring_") || name.startsWith("get_spring_") ? "spring" : "architecture";
        return tool(name, action, schema, arguments, panel, true, false);
    }

    private static String tool(
            String name,
            boolean action,
            String schema,
            List<String> arguments,
            String panel,
            boolean enabled,
            boolean readOnly) {
        return """
                {"name":"%s","description":"test","panel":"%s","action":%s,
                 "schema":"%s","arguments":[%s],"panelEnabled":%s,"panelReadOnly":%s}
                """.formatted(
                        name,
                        panel,
                        action,
                        schema,
                        arguments.stream()
                                .map(value -> "\"" + value + "\"")
                                .reduce((a, b) -> a + "," + b)
                                .orElse(""),
                        enabled,
                        readOnly);
    }

    private static String runtimeInsightsReport(boolean lowFootprintActive, boolean capturePaused) {
        String lowFootprint = lowFootprintActive ? """
                  ,"lowFootprint":{"active":true,"paused":%s,"hint":"private hint","components":["private component"]}
                  """.formatted(capturePaused) : "";
        return """
                {"available":true,"unavailableReason":null,"query":"","requests":3,
                 "coverage":[{"source":"agent.executors","events":4,"byRequestId":1,"byExecutionId":1,
                              "byTraceId":1,"unlinked":1,"dropped":2}],
                 "checksNotRun":["private check reason"],
                 "observations":[
                   {"id":"private-id","kind":"repeated-selects","status":"OBSERVED","subject":"private-route",
                    "sentence":"private sentence","eligible":0,"affected":5,"tier":"REQUEST_ID",
                    "exemplarRequestId":"private-request","verify":"private-verify","listed":true},
                   {"id":"another-private-id","kind":"repeated-selects","status":"OBSERVED","subject":"other-route",
                    "sentence":"private sentence","eligible":2,"affected":4,"tier":"REQUEST_ID",
                    "exemplarRequestId":"private-request","verify":"private-verify","listed":true}],
                 "omitted":2,"notExercised":["private-route"],"notExercisedOmitted":1,
                 "limitations":["private limitation"],"next":[{"tool":"private-next"}]%s}
                 """.formatted(lowFootprint);
    }

    private String overview() {
        if (changeFrameworkBeforeFinalIdentity && overviewRequests == 3) {
            frameworkName = "Spring Boot";
        }
        return """
                {"bootUiVersion":"2.2.0","frameworkName":"%s",
                 "run":{"instanceId":"%s","runId":"0123abcd"}}
                """.formatted(frameworkName, currentInstance);
    }

    private static String asLegacyWitness(String value) {
        JsonValue current = JsonValue.parse(value);
        Map<String, JsonValue> legacy = new LinkedHashMap<>();
        for (String key : current.names()) {
            if (!"frameworkName".equals(key)) {
                legacy.put(key, "schemaVersion".equals(key) ? JsonValue.of(1) : current.get(key));
            }
        }
        return JsonWriter.pretty(JsonValue.object(legacy));
    }

    private String report(boolean medium) {
        String results = finding
                ? java.util.stream.IntStream.range(0, findingRuleCount)
                        .mapToObj(index -> """
                                {"id":"ARCH-%03d","severity":"%s","status":"VIOLATION","violationCount":%d,
                                 "dismissed":false,"suppressedCount":0}
                                """.formatted(
                                        reverseFindingOrder ? findingRuleCount - index : index + 1,
                                        medium ? "MEDIUM" : "HIGH",
                                        findingCount / findingRuleCount))
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
                reportSeverityCounts != null
                        ? reportSeverityCounts
                        : severityCounts(finding && !medium ? findingCount : 0, finding && medium ? findingCount : 0),
                finding ? findingCount : 0,
                finding ? findingCount : 0);
    }

    private static String severityCounts(int high, int medium) {
        return """
                [{"severity":"CRITICAL","count":0},{"severity":"HIGH","count":%d},
                 {"severity":"MEDIUM","count":%d},{"severity":"LOW","count":0},{"severity":"INFO","count":0}]
                """.formatted(high, medium);
    }

    private String detailPage(String ruleId, int offset, int limit) {
        detailRequests++;
        int perRule = findingCount / findingRuleCount;
        int returned = Math.min(limit, perRule - offset);
        int reportedTotal = mismatchSecondPage && offset > 0 ? perRule + 1 : perRule;
        return """
                {"scanId":"scan-12345678","ruleId":"%s","violationCount":%d,"retainedCount":%d,
                 "truncated":false,"violations":[%s],
                 "page":{"total":%d,"matched":%d,"offset":%d,"limit":%d,"returned":%d,"hasMore":%s},
                 "identities":[],"suppressed":[],"suppressedCount":0}
                """.formatted(
                        ruleId,
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
