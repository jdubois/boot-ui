package io.github.jdubois.bootui.engine.progress;

import java.util.regex.Pattern;

/**
 * A fixed, human-readable label for a phase of an operation, sent to the client as the progress {@code message}.
 *
 * <p>Labels are engine-authored constants, never built from runtime values. The pattern admits letters, digits,
 * spaces, and {@code , . ' ( ) -} only, so a label has no path, URL, {@code :}, or {@code =}; an architecture test
 * keeps every call in a static initializer, which is what keeps runtime values out. A violating label fails when its
 * constant is created.
 *
 * @param label the label, 1 to 80 characters, starting with a letter
 */
public record ProgressPhase(String label) {

    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z][A-Za-z0-9 ,.'()-]{0,79}");

    public ProgressPhase {
        if (label == null || !SAFE_LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("Progress phase labels are fixed, plain-text constants: " + label);
        }
    }

    /** A phase with {@code label}. */
    public static ProgressPhase of(String label) {
        return new ProgressPhase(label);
    }
}
