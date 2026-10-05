package io.github.jdubois.bootui.client;

/**
 * A failure to reach the application or to make sense of what it sent back.
 *
 * <p>Distinct from a refused tool call, which is a normal {@link ToolResult} carrying a non-success outcome:
 * this is the request never completing, so there is nothing to report about the application itself.
 */
public class BootUiClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BootUiClientException(String message) {
        super(message);
    }

    public BootUiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
