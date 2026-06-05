package io.github.jdubois.bootui.autoconfigure.copilotfix;

import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;
import java.nio.file.Path;

/**
 * Drives a single Copilot agent session to remediate a finding inside an isolated worktree.
 *
 * <p>BootUI does not depend on the Copilot SDK directly, so the actual session is created behind
 * this interface. The production implementation ({@link SdkCopilotFixAgent}) binds to the SDK when
 * it is on the classpath; tests provide a fake. Implementations must never log or echo the token,
 * and must confine edits to {@link Context#worktree()}.
 */
interface CopilotFixAgent {

    /**
     * Runs the agent for the given context, reporting progress through {@code listener}. Edits are
     * left on disk inside {@link Context#worktree()} for the caller to capture as a diff.
     */
    void run(Context context, CopilotFixListener listener);

    /**
     * Immutable inputs for a run.
     *
     * @param worktree the isolated working directory the agent may edit (and only this directory)
     * @param descriptor the finding to remediate
     * @param systemPrompt the constrained system prompt
     * @param userPrompt the finding-specific user prompt
     * @param model the model to use, or {@code null} for the SDK default
     * @param token the resolved GitHub token (never expose or log this)
     */
    record Context(
            Path worktree,
            CopilotFixDescriptorDto descriptor,
            String systemPrompt,
            String userPrompt,
            String model,
            String token) {}
}
