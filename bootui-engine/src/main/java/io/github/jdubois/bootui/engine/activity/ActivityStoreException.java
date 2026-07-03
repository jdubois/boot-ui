package io.github.jdubois.bootui.engine.activity;

/** Unchecked wrapper for a failure in an {@link ActivityStore} operation (typically a {@link java.sql.SQLException}). */
public class ActivityStoreException extends RuntimeException {

    public ActivityStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
