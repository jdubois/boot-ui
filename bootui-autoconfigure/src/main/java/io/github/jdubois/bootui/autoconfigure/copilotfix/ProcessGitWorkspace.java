package io.github.jdubois.bootui.autoconfigure.copilotfix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link GitWorkspace} backed by the local {@code git} command-line tool.
 *
 * <p>Uses {@code git worktree} so the isolated branch is checked out into a throwaway directory,
 * leaving the developer's primary working tree and current branch untouched. All git invocations
 * are bounded by a timeout and capture a bounded amount of output.
 */
final class ProcessGitWorkspace implements GitWorkspace {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** Hard cap on captured diff size so a runaway change cannot exhaust memory. */
    private static final int MAX_DIFF_CHARS = 200_000;

    private final Path repoRoot;

    ProcessGitWorkspace(Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    @Override
    public boolean available() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (!Files.isDirectory(repoRoot)) {
            return "Working directory does not exist";
        }
        Exec inside = run(repoRoot, "rev-parse", "--is-inside-work-tree");
        if (!inside.success() || !"true".equals(inside.stdout().trim())) {
            return "Not a git repository";
        }
        Exec head = run(repoRoot, "rev-parse", "--verify", "HEAD");
        if (!head.success()) {
            return "Git repository has no commits yet";
        }
        return null;
    }

    @Override
    public Isolated createIsolated(String branch) {
        String reason = unavailableReason();
        if (reason != null) {
            throw new GitWorkspaceException(reason);
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("bootui-copilot-fix-");
        } catch (IOException ex) {
            throw new GitWorkspaceException("Could not create worktree directory", ex);
        }
        // git worktree add -b <branch> <dir> HEAD creates the branch and checks it out in <dir>.
        Exec add = run(repoRoot, "worktree", "add", "-b", branch, directory.toString(), "HEAD");
        if (!add.success()) {
            throw new GitWorkspaceException(
                    "Could not create isolated worktree: " + add.stderr().trim());
        }
        return new Isolated(directory, branch);
    }

    @Override
    public Diff capture(Isolated isolated) {
        Path dir = isolated.directory();
        // Stage everything (including new and deleted files) so the diff reflects all edits.
        run(dir, "add", "-A");
        Exec numstat = run(dir, "diff", "--cached", "--numstat");
        int filesChanged = 0;
        if (numstat.success()) {
            for (String line : numstat.stdout().split("\n")) {
                if (!line.isBlank()) {
                    filesChanged++;
                }
            }
        }
        Exec diff = run(dir, "diff", "--cached");
        String unified = diff.success() ? diff.stdout() : "";
        if (unified.length() > MAX_DIFF_CHARS) {
            unified = unified.substring(0, MAX_DIFF_CHARS) + "\n... (diff truncated)";
        }
        return new Diff(unified, filesChanged);
    }

    @Override
    public void cleanup(Isolated isolated, boolean deleteBranch) {
        run(repoRoot, "worktree", "remove", "--force", isolated.directory().toString());
        if (deleteBranch) {
            run(repoRoot, "branch", "-D", isolated.branch());
        }
    }

    private Exec run(Path workingDir, String... gitArgs) {
        List<String> command = new ArrayList<>(gitArgs.length + 1);
        command.add("git");
        for (String arg : gitArgs) {
            command.add(arg);
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(false)
                    .start();
            process.getOutputStream().close();
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Exec(false, "", "git command timed out");
            }
            return new Exec(
                    process.exitValue() == 0,
                    new String(out, StandardCharsets.UTF_8),
                    new String(err, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new Exec(false, "", ex.getMessage() == null ? "git not available" : ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Exec(false, "", "interrupted");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private record Exec(boolean success, String stdout, String stderr) {}
}
