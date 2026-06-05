package io.github.jdubois.bootui.core;

import java.util.Set;

/**
 * Detects whether a configuration property name or value looks like a secret
 * and produces a redacted display value for it.
 *
 * <p>BootUI uses this helper everywhere configuration values are exposed to the
 * browser to avoid leaking credentials during local development.</p>
 */
public final class SecretMasker implements MaskingStrategy {

    public static final String MASKED_VALUE = "******";

    private final MaskingStrategy strategy;

    public SecretMasker() {
        this.strategy = new KeywordPatternMasker();
    }

    public SecretMasker(Set<String> keyPatterns) {
        this.strategy = new KeywordPatternMasker(keyPatterns);
    }

    public SecretMasker(MaskingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Returns true when the property name suggests a sensitive value.
     */
    @Override
    public boolean isSecret(String propertyName) {
        return strategy.isSecret(propertyName);
    }

    /**
     * Returns true when either the property name or the value looks like a secret.
     */
    @Override
    public boolean shouldMask(String propertyName, Object value) {
        return strategy.shouldMask(propertyName, value);
    }

    /**
     * Returns the original value or {@link #MASKED_VALUE} when the property is
     * detected as sensitive.
     */
    @Override
    public Object mask(String propertyName, Object value) {
        return strategy.mask(propertyName, value);
    }
}
