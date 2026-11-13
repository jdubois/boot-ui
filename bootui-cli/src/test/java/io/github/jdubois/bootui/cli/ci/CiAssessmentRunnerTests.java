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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CiAssessmentRunnerTests {

    @TempDir
    Path temp;

    private HttpServer server;
    private int targetRequests;
    private int catalogStatus = 200;
    private boolean finding;
    private int findingRuleCount = 1;
    private boolean springFails = true;
    private int springReportStatus = 200;
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
    private final Map<String, UnaryOperator<String>> responseTransforms = new LinkedHashMap<>();

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
    void accountsForTwoExplicitWindowsEvenWhenTheyShareOneRuntimeIdentity() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", policy, List.of("web", "worker"));
        CiCollection.Window web = collectionWindow(root, plan, "web");
        CiCollection.Window worker = collectionWindow(root, plan, "worker");
        assertThat(web.attached()).isTrue();
        assertThat(worker.attached()).isTrue();
        assertThat(web.finish()).isZero();
        assertThat(web.finish()).isZero();
        assertThat(worker.finish()).isZero();

        JsonValue first = JsonValue.parse(
                Files.readString(CiCollection.evidenceDirectory(root, "web").resolve("bundle.json")));
        JsonValue second = JsonValue.parse(
                Files.readString(CiCollection.evidenceDirectory(root, "worker").resolve("bundle.json")));
        assertThat(first.get("instanceId").asString(null))
                .isEqualTo(second.get("instanceId").asString(null));
        assertThat(first.get("runId").asString(null))
                .isEqualTo(second.get("runId").asString(null));
        assertThat(first.get("assessmentId").asString(null))
                .isNotEqualTo(second.get("assessmentId").asString(null));
        CiCollection.Result checked = CiCollection.check(root, plan, "workflow:1");
        assertThat(checked.status()).isEqualTo("PASS");
        assertThat(checked.exitCode()).isZero();
        assertThat(checked.contexts())
                .extracting(CiCollection.ContextResult::contextId)
                .containsExactly("web", "worker");
    }

    @Test
    void reportsTheEvidenceDirectoryForCompletedWindows() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();

        int exitCode = BootUiCli.run(
                new String[] {
                    "ci",
                    "check-contexts",
                    "--root",
                    root.toString(),
                    "--assessment-id",
                    "workflow:1",
                    "--plan-sha256",
                    plan
                },
                Map.of(),
                false,
                new PrintWriter(output, true),
                new PrintWriter(errors, true));

        assertThat(exitCode).isZero();
        assertThat(errors.toString()).isEmpty();
        assertThat(output.toString())
                .contains("web: PASS (reports: " + CiCollection.evidenceDirectory(root, "web") + ")");
    }

    @Test
    void expectedWindowCheckDoesNotContactTheApplication() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        int requestsBefore = targetRequests;

        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
        assertThat(targetRequests).isEqualTo(requestsBefore);
    }

    @Test
    void missingEvidenceTakesPrecedenceOverAProvenViolation() throws Exception {
        finding = true;
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web", "worker"));
        CiCollection.Window web = collectionWindow(root, plan, "web");
        assertThat(web.finish()).isEqualTo(3);
        CiCollection.Result incomplete = CiCollection.check(root, plan, "workflow:1");
        assertThat(incomplete.exitCode()).isEqualTo(1);
        assertThat(incomplete.contexts())
                .extracting(CiCollection.ContextResult::status)
                .containsExactly("VIOLATION", "NOT_ASSESSED");
        finding = false;
        assertThat(collectionWindow(root, plan, "worker").finish()).isZero();
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(3);
    }

    @Test
    void duplicateClaimsLeaveAStickyFailureEvenAfterTheFirstWindowPasses() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        CiCollection.Window duplicate = collectionWindow(root, plan, "web");
        assertThat(duplicate.attached()).isFalse();
        assertThat(duplicate.attachFailureCode()).isEqualTo("DUPLICATE_CONTEXT");
        assertThat(duplicate.finish()).isEqualTo(1);
        assertThat(CiCollection.check(root, plan, "workflow:1").errors()).contains("REFUSED_CONTEXT_CLAIM");
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void concurrentlyClaimingTheSameWindowCannotProduceAGreenGate() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CountDownLatch ready = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<CiCollection.Window> first = executor.submit(() -> {
                ready.await();
                return collectionWindow(root, plan, "web");
            });
            Future<CiCollection.Window> second = executor.submit(() -> {
                ready.await();
                return collectionWindow(root, plan, "web");
            });
            ready.countDown();
            List<CiCollection.Window> windows =
                    List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(windows.stream().filter(CiCollection.Window::attached).count())
                    .isEqualTo(1);
            for (CiCollection.Window window : windows) {
                assertThat(window.finish()).isIn(0, 1);
            }
            assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void separateModuleProcessesCanCompleteDifferentPlannedWindows() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web", "worker"));
        Path webModule = Files.createDirectory(temp.resolve("web-module"));
        Path workerModule = Files.createDirectory(temp.resolve("worker-module"));
        Process web = collectionProcess(webModule, root, plan, "web");
        Process worker = collectionProcess(workerModule, root, plan, "worker");
        try {
            assertThat(processResult(web)).isEqualTo("0:ATTACHED");
            assertThat(processResult(worker)).isEqualTo("0:ATTACHED");
            assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isZero();
        } finally {
            web.destroyForcibly();
            worker.destroyForcibly();
        }
    }

    @Test
    void separateProcessesCannotBothClaimTheSameWindow() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        Process first = collectionProcess(temp, root, plan, "web");
        Process second = collectionProcess(temp, root, plan, "web");
        try {
            assertThat(List.of(processResult(first), processResult(second)))
                    .containsExactlyInAnyOrder("0:ATTACHED", "1:DUPLICATE_CONTEXT");
            assertThat(CiCollection.check(root, plan, "workflow:1").errors()).contains("REFUSED_CONTEXT_CLAIM");
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @Test
    void unplannedAndInvalidPlanAttemptsPoisonOtherwisePassingEvidence() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path unplannedRoot = temp.resolve("unplanned");
        String unplannedPlan = prepareCollection(unplannedRoot, "workflow:1", policy, List.of("web"));
        assertThat(collectionWindow(unplannedRoot, unplannedPlan, "web").finish())
                .isZero();
        CiCollection.Window unplanned = collectionWindow(unplannedRoot, unplannedPlan, null);
        assertThat(unplanned.attachFailureCode()).isEqualTo("UNEXPECTED_CONTEXT");
        assertThat(CiCollection.check(unplannedRoot, unplannedPlan, "workflow:1")
                        .exitCode())
                .isEqualTo(1);

        Path invalidRoot = temp.resolve("invalid-plan");
        String validPlan = prepareCollection(invalidRoot, "workflow:1", policy, List.of("web"));
        assertThat(collectionWindow(invalidRoot, validPlan, "web").finish()).isZero();
        CiCollection.Window invalid = collectionWindow(invalidRoot, "0".repeat(64), "web");
        assertThat(invalid.attachFailureCode()).isEqualTo("INVALID_PLAN_OR_POLICY");
        assertThat(CiCollection.check(invalidRoot, validPlan, "workflow:1").exitCode())
                .isEqualTo(1);
    }

    @Test
    void refusedAttachLeavesTheWorkloadFreeToRunAndTheGateFailsClosed() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CiCollection.Window window =
                CiCollection.begin(root, plan, "workflow:1", "web", "http://localhost:8080", "/bootui/api", 5);
        assertThat(window.attached()).isFalse();
        assertThat(window.attachFailureCode()).isEqualTo("INVALID_TARGET_OR_OPTIONS");
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url() + "/bootui/api/overview"))
                .GET()
                .build();
        assertThat(java.net.http.HttpClient.newHttpClient()
                        .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        assertThat(window.finish()).isEqualTo(1);
        assertThat(CiCollection.check(root, plan, "workflow:1")
                        .contexts()
                        .get(0)
                        .errorCode())
                .isEqualTo("INVALID_TARGET_OR_OPTIONS");
    }

    @Test
    void postContactAttachRefusalStillAllowsWorkloadAndFailsTheFinalGate() throws Exception {
        catalogStatus = 503;
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CiCollection.Window window = collectionWindow(root, plan, "web");
        assertThat(window.attached()).isFalse();
        assertThat(window.attachFailureCode()).isEqualTo("TARGET_REQUEST_FAILED");
        assertThat(targetRequests).isEqualTo(1);

        var workloadRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url() + "/bootui/api/overview"))
                .GET()
                .build();
        assertThat(java.net.http.HttpClient.newHttpClient()
                        .send(workloadRequest, java.net.http.HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        assertThat(targetRequests).isEqualTo(2);
        assertThat(window.finish()).isEqualTo(1);
        assertThat(targetRequests).isEqualTo(2);
        assertThat(CiCollection.check(root, plan, "workflow:1")
                        .contexts()
                        .get(0)
                        .errorCode())
                .isEqualTo("TARGET_REQUEST_FAILED");
    }

    @Test
    void targetLossBeforeFinishCannotProduceACompletedWindow() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CiCollection.Window window = collectionWindow(root, plan, "web");
        assertThat(window.attached()).isTrue();
        server.stop(0);
        server = null;

        assertThat(window.finish()).isEqualTo(1);
        CiCollection.ContextResult result =
                CiCollection.check(root, plan, "workflow:1").contexts().get(0);
        assertThat(result.status()).isEqualTo("NOT_ASSESSED");
        assertThat(result.errorCode()).isEqualTo("ASSESSMENT_NOT_COMPLETED");
    }

    @Test
    void snapshotsThePolicyAndRejectsLaterPlanOrPolicyDrift() throws Exception {
        startServer();
        Path policy = writePolicy();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", policy, List.of("web"));
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"low"}}}
                """);
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isZero();
        assertThat(CiCollection.check(root, "0".repeat(64), "workflow:1").exitCode())
                .isEqualTo(1);
        Files.writeString(root.resolve("policy.json"), Files.readString(policy));
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void refusesCrossContextReplayEvenWhenTheCompletionHashesAreRecomputed() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web", "worker"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        assertThat(collectionWindow(root, plan, "worker").finish()).isZero();
        Path web = CiCollection.evidenceDirectory(root, "web");
        Path worker = CiCollection.evidenceDirectory(root, "worker");
        for (String file : List.of("witness.json", "bundle.json")) {
            byte[] replacement = Files.readAllBytes(web.resolve(file));
            Files.write(worker.resolve(file), replacement);
            replaceJsonField(
                    worker.resolve("completion.json"),
                    file.equals("witness.json") ? "witnessSha256" : "bundleSha256",
                    JsonValue.of(CiEvidenceStore.sha256(replacement)));
        }
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void checksWitnessAndBundleIdentityEvenWhenTheWitnessHashIsRecomputed() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        Path slot = CiCollection.evidenceDirectory(root, "web");
        replaceJsonField(slot.resolve("witness.json"), "instanceId", JsonValue.of("ffffffff"));
        replaceJsonField(
                slot.resolve("completion.json"),
                "witnessSha256",
                JsonValue.of(CiEvidenceStore.sha256(Files.readAllBytes(slot.resolve("witness.json")))));
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void outputCollisionsNeverOverwriteExistingEvidenceOrProduceACompletedGate() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CiCollection.Window window = collectionWindow(root, plan, "web");
        Path existing = CiCollection.evidenceDirectory(root, "web").resolve("bundle.json");
        Files.writeString(existing, "do not replace");
        assertThatThrownBy(window::finish).isInstanceOf(IOException.class);
        assertThat(Files.readString(existing)).isEqualTo("do not replace");
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void failedCompletionWriteCannotBeReportedAsSuccessByARepeatedFinish() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        CiCollection.Window window = collectionWindow(root, plan, "web");
        Path completion = CiCollection.evidenceDirectory(root, "web").resolve("completion.json");
        Files.writeString(completion, "do not replace");

        assertThatThrownBy(window::finish).isInstanceOf(IOException.class);
        assertThat(window.finish()).isEqualTo(1);
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedCompletionAndUnexpectedEvidence() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        Path slot = CiCollection.evidenceDirectory(root, "web");
        Path unexpected = slot.resolve("unexpected");
        Files.writeString(unexpected, "unexpected");
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
        Files.delete(unexpected);
        replaceJsonField(slot.resolve("completion.json"), "status", JsonValue.NULL);
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsSymbolicLinksInTheEvidenceRootAndWindow() throws Exception {
        startServer();
        Path root = temp.resolve("contexts");
        String plan = prepareCollection(root, "workflow:1", writePolicy(), List.of("web"));
        assertThat(collectionWindow(root, plan, "web").finish()).isZero();
        Path witness = CiCollection.evidenceDirectory(root, "web").resolve("witness.json");
        Path outside = temp.resolve("outside.json");
        Files.writeString(outside, Files.readString(witness));
        Files.delete(witness);
        Files.createSymbolicLink(witness, outside);
        assertThat(CiCollection.check(root, plan, "workflow:1").exitCode()).isEqualTo(1);
        assertThat(Files.isRegularFile(outside)).isTrue();
        Path linkedRoot = temp.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, root);
        assertThat(CiCollection.check(linkedRoot, plan, "workflow:1").exitCode())
                .isEqualTo(1);
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
    void skipsTheOptionalQuarkusApplicationAdvisorBeforeTheWorkload() throws Exception {
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

        assertThat(exitCode).isZero();
        assertThat(cliOutput.toString()).doesNotContain("UNSUPPORTED_ADVISOR_FOR_FRAMEWORK");
        assertThat(Files.readString(witness)).contains("\"preflight\": \"FRAMEWORK_UNAVAILABLE\"");
        assertThat(overviewRequests).isEqualTo(1);
        assertThat(detailRequests).isZero();

        int assessmentExit = runner(policy, witness, output).assess();
        assertThat(assessmentExit)
                .withFailMessage(() -> {
                    try {
                        return Files.readString(output.resolve("bundle.json"));
                    } catch (IOException e) {
                        return "bundle unavailable: " + e;
                    }
                })
                .isZero();
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"PASS\"", "\"reason\": \"FRAMEWORK_UNAVAILABLE\"");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
        assertThat(overviewRequests).isEqualTo(3);
        assertThat(detailRequests).isZero();
    }

    @Test
    void skipsAnUnevaluableOptionalAdvisorButKeepsRequiredFindings() throws Exception {
        finding = true;
        springFails = true;
        startServer();
        Path policy = writePolicyWithOptionalSpringAdvisor();
        Path witness = temp.resolve("optional-witness.json");
        Path output = temp.resolve("optional-assessment");
        CiAssessmentRunner runner = runner(policy, witness, output);
        assertThat(runner.attach()).isZero();

        assertThat(runner.assess()).isEqualTo(3);
        String bundle = Files.readString(output.resolve("bundle.json"));
        assertThat(bundle)
                .contains("\"status\": \"VIOLATION\"", "\"reason\": \"SCAN_FAILED\"", "\"name\": \"architecture\"");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("| architecture | ARCH-001 | HIGH | 1 | yes |", "| spring | false | SKIPPED |");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(3);
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

    @ParameterizedTest
    @CsvSource({
        "null,0,true",
        "true,0,true",
        "42,0,true",
        "{},0,true",
        "[],0,true",
        "null,200,true",
        "true,200,true",
        "42,200,true",
        "{},200,true",
        "[],200,true",
        "null,0,false",
        "null,200,false"
    })
    void rejectsNonStringRetainedDetailsBeforeReturningAnyFindingVerdict(String detail, int offset, boolean medium)
            throws Exception {
        finding = true;
        findingCount = offset + 1;
        architectureFindingsMedium = medium;
        responseTransforms.put("get_architecture_rule_violations", body -> {
            if (JsonValue.parseStrict(body).get("page").get("offset").asInt(-1) != offset) {
                return body;
            }
            return replaceResponseField(body, "violations", JsonValue.array(List.of(JsonValue.parseStrict(detail))));
        });
        startServer();
        Path policy = writePolicy();
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, temp.resolve("witness.json"), output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(detailRequests).isEqualTo(offset == 0 ? 1 : 2);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"NOT_ASSESSED\"", "\"code\": \"DETAIL_PAGE_INCONSISTENT\"");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);
        assertThat(Files.readString(output.resolve("junit.xml"))).contains("<error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"private-finding-payload\"", "\"\""})
    void acceptsStringRetainedDetailsBelowTheThresholdWithoutPublishingThem(String detail) throws Exception {
        finding = true;
        architectureFindingsMedium = true;
        responseTransforms.put(
                "get_architecture_rule_violations",
                body -> replaceResponseField(
                        body, "violations", JsonValue.array(List.of(JsonValue.parseStrict(detail)))));
        startServer();
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("| architecture | true | ASSESSED | 1 | 0 | - |");
        for (String name : List.of("bundle.json", "summary.md", "junit.xml")) {
            assertThat(Files.readString(output.resolve(name))).doesNotContain("private-finding-payload");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UNAVAILABLE",
                "PANEL_DISABLED",
                "PANEL_READ_ONLY",
                "EVIDENCE_UNEVALUABLE",
                "REQUEST_FAILED",
                "SUPPRESSION_PRESENT",
                "DETAIL_PAGE_INCONSISTENT"
            })
    void showsTheStableOptionalAdvisorSkipReasonInAllReports(String reason) throws Exception {
        springFails = false;
        architectureFindingsMedium = true;
        switch (reason) {
            case "UNAVAILABLE", "PANEL_DISABLED", "PANEL_READ_ONLY" ->
                responseTransforms.put("cli", body -> {
                    List<JsonValue> tools = JsonValue.parseStrict(body).get("tools").values().stream()
                            .filter(tool -> !"UNAVAILABLE".equals(reason)
                                    || !tool.get("name").asString("").contains("spring"))
                            .map(tool -> {
                                if (!"spring_scan".equals(tool.get("name").asString(null))) {
                                    return tool;
                                }
                                String field = "PANEL_DISABLED".equals(reason) ? "panelEnabled" : "panelReadOnly";
                                return JsonValue.parseStrict(replaceResponseField(
                                        tool.toJson(), field, JsonValue.of(!"PANEL_DISABLED".equals(reason))));
                            })
                            .toList();
                    return replaceResponseField(body, "tools", JsonValue.array(tools));
                });
            case "EVIDENCE_UNEVALUABLE" ->
                responseTransforms.put(
                        "get_spring_report",
                        body -> replaceResponseField(body, "evidence", JsonValue.parseStrict("""
                            {"usable":false,"coverageComplete":false,"limitations":[
                              "SPRING-ASYNC-001: private-evidence-reason",
                              "private-evidence-reason",
                              "SEC-PRIVATESECRET-123: private-evidence-reason",
                              "SPRING-ASYNC-001: private-finding-payload"
                            ]}
                            """)));
            case "REQUEST_FAILED" -> responseTransforms.put("get_spring_report", body -> "private-invalid-json");
            case "SUPPRESSION_PRESENT" -> {
                finding = true;
                responseTransforms.put(
                        "get_spring_report", body -> body.replace("\"dismissed\":false", "\"dismissed\":true"));
            }
            case "DETAIL_PAGE_INCONSISTENT" -> {
                finding = true;
                responseTransforms.put(
                        "get_spring_rule_violations",
                        body -> replaceResponseField(
                                body, "violations", JsonValue.array(List.of(JsonValue.parseStrict("null")))));
            }
            default -> throw new AssertionError("Unexpected reason: " + reason);
        }
        startServer();
        Path policy = writePolicyWithOptionalSpringAdvisor();
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(policy, temp.resolve("witness.json"), output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isZero();
        assertThat(Files.readString(output.resolve("bundle.json"))).contains("\"reason\": \"" + reason + "\"");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("| spring | false | SKIPPED | 0 | 0 | " + reason + " |");
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("1 of 2 advisors assessed", "not that skipped advisors passed");
        if ("EVIDENCE_UNEVALUABLE".equals(reason)) {
            assertThat(Files.readString(output.resolve("summary.md")))
                    .contains(
                            "usable=false",
                            "coverageComplete=false",
                            "reported componentsAnalyzed=",
                            "reported coverage limitations=4");
        } else if ("UNAVAILABLE".equals(reason)) {
            assertThat(Files.readString(output.resolve("summary.md")))
                    .contains("Required diagnostic tools are not advertised");
        }
        var junit = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(output.resolve("junit.xml").toFile());
        var skipped = junit.getElementsByTagName("skipped");
        assertThat(skipped.getLength()).isEqualTo(1);
        assertThat(skipped.item(0).getAttributes().getNamedItem("message").getNodeValue())
                .contains(reason);
        assertThat(junit.getDocumentElement().getAttribute("skipped")).isEqualTo("1");
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isZero();
        for (String name : List.of("bundle.json", "summary.md", "junit.xml")) {
            assertThat(Files.readString(output.resolve(name)))
                    .doesNotContain(
                            "private-finding-payload",
                            "private-evidence-reason",
                            "private-invalid-json",
                            "PRIVATESECRET");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PARTIAL",
                "UNUSABLE",
                "INCOMPLETE",
                "UNKNOWN_SEVERITY",
                "RULE_DISMISSED",
                "FINDING_SUPPRESSED",
                "DETAIL_SUPPRESSED",
                "TRUNCATED",
                "OVERSIZED_DETAIL"
            })
    void requiredIncompleteOrSuppressedEvidenceCannotPass(String problem) throws Exception {
        finding = true;
        architectureFindingsMedium = true;
        String expectedCode;
        switch (problem) {
            case "PARTIAL" -> {
                responseTransforms.put("get_architecture_report", body -> body.replace("\"SCANNED\"", "\"PARTIAL\""));
                expectedCode = "SCAN_INCOMPLETE";
            }
            case "UNUSABLE", "INCOMPLETE" -> {
                responseTransforms.put(
                        "get_architecture_report",
                        body -> replaceResponseField(
                                body, "evidence", JsonValue.parseStrict("""
                                {"usable":%s,"coverageComplete":false,"limitations":["private-evidence-reason"]}
                                """.formatted("INCOMPLETE".equals(problem)))));
                expectedCode = "EVIDENCE_UNEVALUABLE";
            }
            case "UNKNOWN_SEVERITY" -> {
                responseTransforms.put(
                        "get_architecture_report",
                        body -> body.replace("\"severity\":\"MEDIUM\"", "\"severity\":\"UNKNOWN\""));
                expectedCode = "UNKNOWN_SEVERITY";
            }
            case "RULE_DISMISSED" -> {
                responseTransforms.put(
                        "get_architecture_report", body -> body.replace("\"dismissed\":false", "\"dismissed\":true"));
                expectedCode = "SUPPRESSION_PRESENT";
            }
            case "FINDING_SUPPRESSED" -> {
                responseTransforms.put(
                        "get_architecture_report",
                        body -> replaceResponseField(body, "suppression", JsonValue.parseStrict("""
                                {"suppressedFindings":1,"incompleteRules":[],
                                 "findings":[{"ruleId":"ARCH-001","identity":"private-identity","state":"MATCHED"}],
                                 "omitted":0}
                                """)));
                expectedCode = "SUPPRESSION_PRESENT";
            }
            case "DETAIL_SUPPRESSED" -> {
                findingCount = 201;
                responseTransforms.put("get_architecture_rule_violations", body -> {
                    if (JsonValue.parseStrict(body).get("page").get("offset").asInt(-1) == 0) {
                        return body;
                    }
                    return replaceResponseField(body, "suppressed", JsonValue.array(List.of(JsonValue.of(true))));
                });
                expectedCode = "SUPPRESSION_PRESENT";
            }
            case "TRUNCATED" -> {
                responseTransforms.put(
                        "get_architecture_report", body -> body.replace("\"truncated\":false", "\"truncated\":true"));
                expectedCode = "DETAILS_INCOMPLETE";
            }
            case "OVERSIZED_DETAIL" -> {
                responseTransforms.put(
                        "get_architecture_rule_violations",
                        body -> replaceResponseField(
                                body,
                                "violations",
                                JsonValue.array(List.of(JsonValue.of("private-finding-payload"
                                        .repeat(CiAssessmentRunner.MAX_RESPONSE_BYTES / 20))))));
                expectedCode = "TARGET_REQUEST_FAILED";
            }
            default -> throw new AssertionError("Unexpected problem: " + problem);
        }
        startServer();
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains("\"status\": \"NOT_ASSESSED\"", "\"code\": \"" + expectedCode + "\"");
        for (String name : List.of("bundle.json", "summary.md", "junit.xml")) {
            assertThat(Files.readString(output.resolve(name)))
                    .doesNotContain("private-finding-payload", "private-evidence-reason", "private-identity");
        }
        if ("DETAIL_SUPPRESSED".equals(problem)) {
            assertThat(detailRequests).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @CsvSource({"20000,0", "20001,1"})
    void enforcesTheRetainedDetailBudgetWithoutRejectingTheExactLimit(int count, int exitCode) throws Exception {
        finding = true;
        architectureFindingsMedium = true;
        findingCount = count;
        startServer();
        Path output = temp.resolve("assessment");
        CiAssessmentRunner runner = runner(writePolicy(), temp.resolve("witness.json"), output);

        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(exitCode);
        assertThat(detailRequests).isEqualTo(100);
        assertThat(Files.readString(output.resolve("bundle.json")))
                .contains(exitCode == 0 ? "\"status\": \"PASS\"" : "\"code\": \"DETAIL_BUDGET_EXCEEDED\"");
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
        assertThat(Files.readString(output.resolve("junit.xml"))).contains("name=\"assessment\"");
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
                .contains(
                        "| architecture | ARCH-001 | HIGH | 1 | yes |",
                        "Assessment errors: `SCAN_FAILED`.",
                        "| spring | true | NOT_ASSESSED | - | - | SCAN_FAILED |");
        String junit = Files.readString(output.resolve("junit.xml"));
        assertThat(junit).contains("<failure", "<error", "name=\"spring\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVIDENCE_UNEVALUABLE", "SCAN_FAILED", "REPORT_FAILED", "TARGET_REQUEST_FAILED"})
    void identifiesRequiredAdvisorFailuresWithoutChangingTheBundle(String reason) throws Exception {
        springFails = "SCAN_FAILED".equals(reason);
        if ("EVIDENCE_UNEVALUABLE".equals(reason)) {
            responseTransforms.put(
                    "get_spring_report", body -> replaceResponseField(body, "evidence", JsonValue.parseStrict("""
                        {"usable":true,"coverageComplete":false,"limitations":["private-evidence-<secret>&"]}
                        """)));
        } else if ("REPORT_FAILED".equals(reason)) {
            springReportStatus = 500;
        } else if ("TARGET_REQUEST_FAILED".equals(reason)) {
            responseTransforms.put("get_spring_report", body -> "private-invalid-json");
        }
        startServer();
        Path policy = writePolicyWithSpringAdvisor();
        Path output = temp.resolve("assessment");
        var runner = runner(policy, temp.resolve("witness.json"), output);
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        JsonValue bundle = JsonValue.parseStrict(Files.readString(output.resolve("bundle.json")));
        assertThat(bundle.get("errors").get(0).get("code").asString(null)).isEqualTo(reason);
        assertThat(bundle.get("advisors").values())
                .noneMatch(advisor -> "spring".equals(advisor.get("name").asString(null)));
        assertThat(CiBundleEvaluator.evaluate(output.resolve("bundle.json"), "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);
        String summary = Files.readString(output.resolve("summary.md"));
        assertThat(summary).contains("| spring | true | NOT_ASSESSED | - | - | " + reason + " |");
        var junit = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(output.resolve("junit.xml").toFile());
        var errors = junit.getElementsByTagName("error");
        assertThat(errors.getLength()).isOne();
        assertThat(errors.item(0)
                        .getParentNode()
                        .getAttributes()
                        .getNamedItem("name")
                        .getNodeValue())
                .isEqualTo("spring");
        assertThat(errors.item(0).getAttributes().getNamedItem("message").getNodeValue())
                .contains(reason);
        assertThat(junit.getElementsByTagName("skipped").getLength()).isZero();
        assertThat(junit.getDocumentElement().getAttribute("tests")).isEqualTo("1");
        if ("EVIDENCE_UNEVALUABLE".equals(reason)) {
            assertThat(summary).contains("usable=true", "coverageComplete=false", "reported coverage limitations=1");
        }
        for (String file : List.of("bundle.json", "summary.md", "junit.xml")) {
            assertThat(Files.readString(output.resolve(file)))
                    .doesNotContain("private-evidence", "<secret>", "private-invalid-json");
        }
    }

    @Test
    void coverageCountsIncludePolicyAdvisorsNotVisitedAfterARequiredFailure() throws Exception {
        responseTransforms.put("cli", body -> {
            var tools = JsonValue.parseStrict(body).get("tools").values().stream()
                    .filter(tool -> !tool.get("name").asString("").contains("architecture"))
                    .toList();
            return replaceResponseField(body, "tools", JsonValue.array(tools));
        });
        startServer();
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":false,"failOn":"high"},
                  "spring":{"required":true,"failOn":"high"},
                  "rest-api":{"required":false,"failOn":"high"},
                  "security":{"required":false,"failOn":"high"}
                }}
                """);
        Path output = temp.resolve("assessment");
        var runner = runner(policy, temp.resolve("witness.json"), output);
        assertThat(runner.attach()).isZero();
        assertThat(runner.assess()).isEqualTo(1);
        assertThat(Files.readString(output.resolve("summary.md")))
                .contains("0 of 4 advisors assessed", "unvisited advisors were not assessed");
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

    private CiCollection.Window collectionWindow(Path root, String plan, String context) throws IOException {
        return CiCollection.begin(root, plan, "workflow:1", context, url(), "/bootui/api", 5);
    }

    private static String prepareCollection(Path root, String assessmentId, Path policy, List<String> contexts)
            throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.getParent())) {
            Assumptions.assumeTrue(
                    stream instanceof SecureDirectoryStream<?>,
                    "The filesystem does not support secure evidence directory access");
        }
        return CiCollection.prepare(root, assessmentId, policy, contexts);
    }

    private Process collectionProcess(Path workingDirectory, Path root, String plan, String context)
            throws IOException {
        String separator = System.getProperty("path.separator");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String absoluteClasspath = java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(separator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(separator));
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        absoluteClasspath,
                        CiCollectionProcessMain.class.getName(),
                        root.toString(),
                        plan,
                        context,
                        url())
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
    }

    private static String processResult(Process process) throws Exception {
        assertThat(process.waitFor(30, TimeUnit.SECONDS))
                .as("collection child exited")
                .isTrue();
        return process.exitValue() + ":"
                + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private static void replaceJsonField(Path file, String name, JsonValue replacement) throws IOException {
        Files.writeString(file, replaceResponseField(Files.readString(file), name, replacement));
    }

    private static String replaceResponseField(String body, String name, JsonValue replacement) {
        JsonValue original = JsonValue.parseStrict(body);
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (String key : original.names()) {
            values.put(key, original.get(key));
        }
        values.put(name, replacement);
        return JsonWriter.pretty(JsonValue.object(values));
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
                  "architecture":{"required":true,"failOn":"high"},
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
        targetRequests++;
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
        UnaryOperator<String> transform = responseTransforms.get(path.substring(path.lastIndexOf('/') + 1));
        if (transform != null) {
            body = transform.apply(body);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        int status = path.endsWith("/tools/get_runtime_insights")
                ? runtimeInsightsStatus
                : path.endsWith("/tools/get_spring_report")
                        ? springReportStatus
                        : path.endsWith("/cli") ? catalogStatus : 200;
        exchange.sendResponseHeaders(status, bytes.length);
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
