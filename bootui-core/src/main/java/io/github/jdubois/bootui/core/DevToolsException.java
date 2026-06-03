package io.github.jdubois.bootui.core;

/**
 * Thrown when interacting with Spring Boot DevTools fails.
 */
public final class DevToolsException extends BootUiException {

    public DevToolsException(String message) {
        super(message);
    }

    public DevToolsException(String message, Throwable cause) {
        super(message, cause);
    }
}
