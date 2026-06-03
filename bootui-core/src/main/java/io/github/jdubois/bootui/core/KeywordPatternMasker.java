package io.github.jdubois.bootui.core;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class KeywordPatternMasker implements MaskingStrategy {

    public static final Set<String> DEFAULT_KEY_PATTERNS = Set.of(
            "password",
            "passwd",
            "passphrase",
            "pwd",
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

    public KeywordPatternMasker() {
        this(DEFAULT_KEY_PATTERNS);
    }

    public KeywordPatternMasker(Set<String> keyPatterns) {
        this.keyPatterns = new LinkedHashSet<>(Objects.requireNonNull(keyPatterns, "keyPatterns"));
    }

    @Override
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

    @Override
    public Object mask(String propertyName, Object value) {
        if (value == null) {
            return null;
        }
        if (isSecret(propertyName)) {
            return "******";
        }
        return value;
    }
}
