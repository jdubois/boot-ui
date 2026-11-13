package io.github.jdubois.bootui.autoconfigure.compatibility;

/** A positively established incompatibility, rendered by Spring Boot's normal failure reporter. */
public final class BootUiCompatibilityException extends IllegalStateException {

    private final String action;

    public BootUiCompatibilityException(String message, String action) {
        super(message);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
