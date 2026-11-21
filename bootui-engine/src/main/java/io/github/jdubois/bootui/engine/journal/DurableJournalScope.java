package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * The complete tenant key of one durable journal run.
 *
 * @param namespace the independent BootUI installation or tenant
 * @param application the application whose journal is stored
 * @param run the run id, unique within the namespace and application
 */
public record DurableJournalScope(String namespace, String application, String run) {

    public static final int MAX_NAMESPACE_LENGTH = 128;
    public static final int MAX_APPLICATION_LENGTH = 128;
    public static final int MAX_RUN_LENGTH = 128;

    public DurableJournalScope {
        namespace = bounded("namespace", namespace, MAX_NAMESPACE_LENGTH);
        application = bounded("application", application, MAX_APPLICATION_LENGTH);
        run = bounded("run", run, MAX_RUN_LENGTH);
        if ("__bootui_internal__".equals(namespace)) {
            throw new IllegalArgumentException("namespace is reserved for durable journal storage");
        }
    }

    /** Validates a scope string before it reaches a fixed-width JDBC projection. */
    static String bounded(String field, String value, int maximum) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }
}
