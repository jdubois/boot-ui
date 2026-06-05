package io.github.jdubois.bootui.autoconfigure.copilotfix;

import java.nio.file.Path;

/**
 * Isolates the edits proposed by a "Fix it with Copilot" run from the developer's working tree.
 *
 * <p>Implementations create a dedicated branch and a separate working directory (a git worktree)
 * so the agent can edit files without touching the branch the developer currently has checked out.
 * After the agent finishes, the proposed changes are captured as a unified diff for review.
 */
interface GitWorkspace {

    /** Whether this directory is a usable git repository with at least one commit. */
    boolean available();

    /** Human-readable reason describing why the workspace is unavailable, or {@code null}. */
    String unavailableReason();

    /**
     * Creates a new branch and an isolated worktree checked out to it, both derived from the
     * current {@code HEAD}. The developer's working tree and current branch are left untouched.
     *
     * @throws GitWorkspaceException when the branch or worktree could not be created
     */
    Isolated createIsolated(String branch);

    /** Captures the agent's proposed edits inside the isolated worktree as a unified diff. */
    Diff capture(Isolated isolated);

    /**
     * Removes the isolated worktree. When {@code deleteBranch} is {@code true} the branch is
     * deleted too (used when the run produced no changes), otherwise the branch is kept so the
     * developer can review and open a pull request from it.
     */
    void cleanup(Isolated isolated, boolean deleteBranch);

    /** An isolated worktree and the branch it is checked out to. */
    record Isolated(Path directory, String branch) {}

    /** The captured result of an isolated run. */
    record Diff(String unified, int filesChanged) {}

    /** Raised when a git operation required for isolation fails. */
    class GitWorkspaceException extends RuntimeException {
        GitWorkspaceException(String message) {
            super(message);
        }

        GitWorkspaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
