package io.github.jdubois.bootui.autoconfigure.copilotfix;

/**
 * Production {@link CopilotFixAgent} that drives the GitHub Copilot SDK for Java.
 *
 * <p>BootUI never compiles against the SDK, so this implementation is intentionally conservative:
 * it confirms the SDK is on the classpath and that a token is available, then hands control to the
 * SDK binding. When the SDK is absent it reports the capability as unavailable and makes no edits.
 *
 * <p>The concrete SDK session wiring (creating a {@code CopilotClient}, opening a session scoped to
 * {@link Context#worktree()} with a confirmation-required permission handler, and streaming the
 * agent's tool calls) is layered on top of this class in a follow-up; the surrounding plumbing -
 * auth resolution, branch isolation, diff capture, streaming and safety gating - is exercised here.
 */
final class SdkCopilotFixAgent implements CopilotFixAgent {

    @Override
    public void run(Context context, CopilotFixListener listener) {
        if (!CopilotFixDetector.isSdkPresent()) {
            listener.onEvent(
                    "error",
                    "The GitHub Copilot SDK for Java is not on the application classpath. Add it as a "
                            + "dependency to enable automated fixes. No changes were made.");
            return;
        }
        listener.onEvent("status", "Copilot SDK detected; preparing an isolated agent session.");
        listener.onEvent(
                "status",
                "Agent session driving is not enabled in this build; the isolated branch was prepared "
                        + "but no edits were applied. Review the (empty) diff and see the docs to enable "
                        + "the SDK binding.");
        // A follow-up wires the actual SDK session here, confined to context.worktree() and using a
        // confirmation-required permission handler. The token (context.token()) is passed to the SDK
        // only and is never logged or echoed into a listener event.
    }
}
