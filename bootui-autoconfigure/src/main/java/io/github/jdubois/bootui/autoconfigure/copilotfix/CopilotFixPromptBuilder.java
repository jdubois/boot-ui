package io.github.jdubois.bootui.autoconfigure.copilotfix;

import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;

/**
 * Builds the constrained system and user prompts handed to the Copilot agent for a finding.
 *
 * <p>Pure and side-effect free so it can be unit tested in isolation. The system prompt narrows the
 * agent to a focused, minimal remediation; the user prompt restates the sanitized finding.
 */
final class CopilotFixPromptBuilder {

    private CopilotFixPromptBuilder() {}

    static String systemPrompt() {
        return """
            You are helping a developer remediate a single finding reported by a local BootUI \
            scanner. Work only inside the current git worktree. Make the smallest, safest change \
            that addresses the finding and nothing else. Do not modify unrelated files, do not \
            change build or CI configuration unless the finding is about it, and do not run \
            destructive commands. Prefer upgrading a vulnerable dependency to the smallest fixed \
            version when the finding is a dependency vulnerability. Explain briefly what you \
            changed and why. Do not commit, push, or open a pull request - leave the edits in the \
            working tree for the developer to review.""";
    }

    static String userPrompt(CopilotFixDescriptorDto descriptor) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fix the following ")
                .append(safe(descriptor.source(), "scanner"))
                .append(" finding.\n\n");
        sb.append("Finding id: ").append(safe(descriptor.findingId(), "(unknown)")).append('\n');
        sb.append("Title: ").append(safe(descriptor.title(), "(none)")).append('\n');
        sb.append("Severity: ").append(safe(descriptor.severity(), "(unknown)")).append('\n');
        if (descriptor.targets() != null && !descriptor.targets().isEmpty()) {
            sb.append("Affected: ").append(String.join(", ", descriptor.targets())).append('\n');
        }
        String summary = descriptor.summary();
        if (summary != null && !summary.isBlank()) {
            sb.append('\n').append("Details:\n").append(summary.strip()).append('\n');
        }
        return sb.toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
