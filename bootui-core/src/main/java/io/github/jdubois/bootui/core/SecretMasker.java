package io.github.jdubois.bootui.core;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Detects whether a configuration property name or value looks like a secret
 * and produces a redacted display value for it.
 *
 * <p>BootUI uses this helper everywhere configuration values are exposed to the
 * browser to avoid leaking credentials during local development.</p>
 */
public final class SecretMasker {

    public static final String MASKED_VALUE = "******";
    private static final Set<String> DEFAULT_KEY_PATTERNS = Set.of(
        "password",
        "passwd",
        "secret",
        "token",
        "key",
        "credential",
        "credentials",
        "private",
        "apikey",
        "api-key",
        "client-secret",
        "client_secret",
        "auth",
        "authorization",
        "session-id",
        "session_id");
    private final Set<String> keyPatterns;

    public SecretMasker() {
        this(DEFAULT_KEY_PATTERNS);
    }

    public SecretMasker(Set<String> keyPatterns) {
        this.keyPatterns = new LinkedHashSet<>(Objects.requireNonNull(keyPatterns, "keyPatterns"));
    }

    /**
     * Returns true when the property name suggests a sensitive value.
     */
    public boolean isSecret(String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return false;
        }
        String normalized = propertyName.toLowerCase(Locale.ROOT);
        for (String pattern : keyPatterns) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the original value or {@link #MASKED_VALUE} when the property is
     * detected as sensitive.
     */
    public Object mask(String propertyName, Object value) {
        if (value == null) {
            return null;
        }
        if (isSecret(propertyName)) {
            return MASKED_VALUE;
        }
        return value;
    }
}
