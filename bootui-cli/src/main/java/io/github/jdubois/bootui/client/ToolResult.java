package io.github.jdubois.bootui.client;

/**
 * One tool call's answer.
 *
 * <p>{@link #rawBody()} is kept verbatim alongside the parsed tree so {@code --json} can emit exactly what
 * the server sent. A client built against one BootUI version must stay usable against another, so payloads
 * are passed through rather than re-serialized from a model the client would have to keep in step.
 *
 * @param toolName the tool that was called
 * @param status the HTTP status the endpoint answered with
 * @param outcome the status interpreted
 * @param rawBody the response body exactly as received
 * @param payload the parsed body, or a missing value when the body was empty or unparseable
 * @param error the server's error message when the call was refused, otherwise {@code null}
 */
public record ToolResult(
        String toolName, int status, ToolOutcome outcome, String rawBody, JsonValue payload, String error) {

    /** Whether the tool ran and answered. */
    public boolean successful() {
        return outcome.successful();
    }

    /** The error message, or a generic description when the server sent none. */
    public String errorMessage() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        return "Request failed with HTTP " + status;
    }
}
