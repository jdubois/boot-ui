package io.github.jdubois.bootui.client;

/**
 * What happened to a tool call, as the command-line endpoint reports it in the HTTP status.
 *
 * <p>The distinction that matters to a caller is between "the tool ran and answered" and "BootUI declined to
 * run it", because the second is a configuration statement about the target application rather than a
 * failure of the request. A CI job that asks a read-only panel to run a scan wants to know that, not to see
 * a generic error.
 */
public enum ToolOutcome {

    /** The tool ran and its payload is available. */
    SUCCESS,

    /** The request was malformed: an unknown argument, a wrong type, or a missing required id. */
    INVALID_REQUEST,

    /** No such tool on this instance. */
    UNKNOWN_TOOL,

    /** The backing panel is disabled, or the panel is read-only and the tool is an action. */
    REFUSED_BY_POLICY,

    /** The action is already running, or the endpoint is at its concurrency limit. */
    BUSY,

    /** The tool exceeded the execution budget. */
    TIMED_OUT,

    /** The command-line endpoint is disabled on this instance. */
    ENDPOINT_DISABLED,

    /** BootUI rejected the caller: no token, a wrong token, or a non-local caller. */
    UNAUTHENTICATED,

    /** The tool failed inside the application, or the server answered something unexpected. */
    SERVER_ERROR;

    /** The outcome an HTTP status maps to. */
    public static ToolOutcome fromStatus(int status) {
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            // Authentication is about the caller, not about how the target's panels are configured, so
            // it must not be reported as a policy refusal a CI job would treat as "skip".
            case 401 -> UNAUTHENTICATED;
            case 403 -> REFUSED_BY_POLICY;
            case 404 -> UNKNOWN_TOOL;
            case 409, 429 -> BUSY;
            case 503 -> ENDPOINT_DISABLED;
            case 504 -> TIMED_OUT;
            default -> SERVER_ERROR;
        };
    }

    /** Whether the tool answered. */
    public boolean successful() {
        return this == SUCCESS;
    }
}
