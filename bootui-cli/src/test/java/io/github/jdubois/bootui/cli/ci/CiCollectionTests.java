package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.jdubois.bootui.cli.BootUiCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiCollectionTests {

    @TempDir
    Path temp;

    private Path policy;

    @BeforeEach
    void writePolicy() throws IOException {
        policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}}}
                """);
    }

    @Test
    void prepareAndCheckCommandsUseAnIndependentlyRetainedDigest() {
        Path root = temp.resolve("contexts");
        CliResult prepared = run(
                "ci", "prepare-contexts",
                "--root", root.toString(),
                "--assessment-id", "run:1",
                "--policy", policy.toString(),
                "--contexts", "web,worker");
        assertThat(prepared.exitCode()).isZero();
        assertThat(prepared.stdout().trim()).matches("[0-9a-f]{64}");
        assertThat(prepared.stderr()).isEmpty();
        CliResult checked = run(
                "ci",
                "check-contexts",
                "--root",
                root.toString(),
                "--assessment-id",
                "run:1",
                "--plan-sha256",
                prepared.stdout().trim());
        assertThat(checked.exitCode()).isEqualTo(1);
        assertThat(checked.stdout())
                .contains("web: NOT_ASSESSED (MISSING_CLAIM)", "worker: NOT_ASSESSED (MISSING_CLAIM)");
        CliResult missingParent = run(
                "ci", "prepare-contexts",
                "--root", temp.resolve("missing-parent/contexts").toString(),
                "--assessment-id", "run:2",
                "--policy", policy.toString(),
                "--contexts", "web");
        assertThat(missingParent.exitCode()).isEqualTo(1);
        assertThat(missingParent.stderr()).contains("The evidence root's parent directory must already exist");
        assertThat(Files.exists(temp.resolve("missing-parent"))).isFalse();
        assertThat(run(
                                "ci", "prepare-contexts",
                                "--root", root.toString(),
                                "--assessment-id", "run:1",
                                "--policy", policy.toString(),
                                "--contexts", "web")
                        .exitCode())
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicateInvalidAndUnboundedExpectedContextsBeforeCreatingTheRoot() {
        for (List<String> contexts : List.<List<String>>of(
                List.of(), List.of("web", "web"), List.of("../web"), List.of(""), List.of("x".repeat(129)))) {
            Path root = temp.resolve("invalid-" + contexts.hashCode());
            assertThatThrownBy(() -> CiCollection.prepare(root, "run:1", policy, contexts))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.exists(root)).isFalse();
        }
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= CiCollectionPlan.MAX_CONTEXTS; i++) {
            tooMany.add("context-" + i);
        }
        Path root = temp.resolve("too-many");
        assertThatThrownBy(() -> CiCollection.prepare(root, "run:1", policy, tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void rejectsRelativeRootsAndInvalidPolicyFiles() throws Exception {
        assertThatThrownBy(() -> CiCollection.prepare(Path.of("relative"), "run:1", policy, List.of("web")))
                .isInstanceOf(IllegalArgumentException.class);
        Files.write(policy, new byte[] {(byte) 0xff});
        assertThatThrownBy(() -> CiCollection.prepare(temp.resolve("invalid-policy"), "run:1", policy, List.of("web")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(temp.resolve("invalid-policy"))).isFalse();
    }

    @Test
    void rejectsMalformedPlansEvenIfTheCallerPinsTheirNewDigest() throws Exception {
        Path root = temp.resolve("contexts");
        CiCollection.prepare(root, "run:1", policy, List.of("web"));
        Path file = root.resolve("plan.json");
        String original = Files.readString(file);
        List<String> malformed = List.of(
                original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
                original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                original.replace("\"contexts\": [", "\"unexpected\": true, \"contexts\": ["),
                original.replace("\"web\"", "\"web\", \"web\""),
                original.replace("\"web\"", "\"../web\""),
                original.replace("\"web\"", "null"),
                original.replace("\"nonce\": \"", "\"nonce\": \"invalid"),
                original + " ".repeat(CiCollectionPlan.MAX_PLAN_BYTES));
        for (String value : malformed) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            CiCollection.Result checked = CiCollection.check(root, CiEvidenceStore.sha256(bytes), "run:1");
            assertThat(checked.exitCode())
                    .as(value.substring(0, Math.min(120, value.length())))
                    .isEqualTo(1);
        }
    }

    @Test
    void aFailedPlanReadLeavesAStickyRefusalAfterThePlanIsRestored() throws Exception {
        Path root = temp.resolve("contexts");
        String plan = CiCollection.prepare(root, "run:1", policy, List.of("web"));
        Path preparedPolicy = root.resolve("policy.json");
        byte[] original = Files.readAllBytes(preparedPolicy);
        Files.writeString(preparedPolicy, "invalid");
        CiCollection.Window refused =
                CiCollection.begin(root, plan, "run:1", "web", "http://127.0.0.1:1234", "/bootui/api", 1);
        assertThat(refused.attachFailureCode()).isEqualTo("INVALID_PLAN_OR_POLICY");
        Files.write(preparedPolicy, original);
        assertThat(CiCollection.check(root, plan, "run:1").errors()).contains("REFUSED_CONTEXT_CLAIM");
    }

    @Test
    void anUnfinishedWindowCannotPass() throws Exception {
        Path root = temp.resolve("contexts");
        String plan = CiCollection.prepare(root, "run:1", policy, List.of("web"));
        CiCollection.Window window =
                CiCollection.begin(root, plan, "run:1", "web", "http://localhost:1234", "/bootui/api", 1);
        assertThat(window.attached()).isFalse();
        assertThat(CiCollection.check(root, plan, "run:1").contexts().get(0).errorCode())
                .isEqualTo("INCOMPLETE_CONTEXT");
        assertThat(window.finish()).isEqualTo(1);
        assertThat(CiCollection.check(root, plan, "run:1").exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsSymlinkedPolicyInputBeforeCreatingTheRoot() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path link = temp.resolve("policy-link.json");
        Files.createSymbolicLink(link, policy);
        Path root = temp.resolve("contexts");
        assertThatThrownBy(() -> CiCollection.prepare(root, "run:1", link, List.of("web")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void rejectsSpecialFilesWithoutBlockingOnARead() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path root = temp.resolve("contexts");
        String plan = CiCollection.prepare(root, "run:1", policy, List.of("web"));
        Path fifo = root.resolve("plan.json");
        Files.delete(fifo);
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertThat(mkfifo.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mkfifo.exitValue()).isZero();
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThat(CiCollection.check(root, plan, "run:1").exitCode())
                        .isEqualTo(1));
    }

    private static CliResult run(String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        int exitCode =
                BootUiCli.run(arguments, Map.of(), false, new PrintWriter(output, true), new PrintWriter(errors, true));
        return new CliResult(exitCode, output.toString(), errors.toString());
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}
}
