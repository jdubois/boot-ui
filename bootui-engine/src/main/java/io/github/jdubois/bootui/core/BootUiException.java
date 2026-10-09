package io.github.jdubois.bootui.core;

/**
 * Base exception for BootUI-specific errors.
 */
public class BootUiException extends RuntimeException {

    public BootUiException(String message) {
        super(message);
    }

    public BootUiException(String message, Throwable cause) {
        super(message, cause);
    }
}
