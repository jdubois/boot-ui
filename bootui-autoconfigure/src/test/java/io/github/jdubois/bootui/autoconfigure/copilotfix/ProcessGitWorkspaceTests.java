package io.github.jdubois.bootui.autoconfigure.copilotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessGitWorkspaceTests {

    @Test
    void reportsUnavailableForNonGitDirectory(@TempDir Path dir) {
        ProcessGitWorkspace workspace = new ProcessGitWorkspace(dir);
        assertThat(workspace.available()).isFalse();
        assertThat(workspace.unavailableReason()).isNotNull();
    }

    @Test
    void isolatesEditsOnADedicatedBranchAndCapturesDiff(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git CLI is required for this test");
        initRepoWithCommit(dir);

        ProcessGitWorkspace workspace = new ProcessGitWorkspace(dir);
        assertThat(workspace.available()).isTrue();

        GitWorkspace.Isolated isolated = workspace.createIsolated("bootui/fix-test-1");
        try {
            // The developer's working tree is untouched: the worktree is a separate directory.
            assertThat(isolated.directory()).isNotEqualTo(dir);
            Files.writeString(isolated.directory().resolve("added.txt"), "hello\n");

            GitWorkspace.Diff diff = workspace.capture(isolated);
            assertThat(diff.filesChanged()).isEqualTo(1);
            assertThat(diff.unified()).contains("added.txt");
        } finally {
            workspace.cleanup(isolated, true);
        }

        // The original working tree never received the new file.
        assertThat(Files.exists(dir.resolve("added.txt"))).isFalse();
    }

    private static void initRepoWithCommit(Path dir) throws Exception {
        run(dir, "git", "init");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("README.md"), "seed\n");
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-m", "seed");
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        byte[] output = process.getInputStream().readAllBytes();
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Command failed: " + String.join(" ", command) + "\n" + new String(output, StandardCharsets.UTF_8));
        }
    }
}
