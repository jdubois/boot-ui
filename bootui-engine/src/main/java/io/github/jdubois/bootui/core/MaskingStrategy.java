package io.github.jdubois.bootui.core;

public interface MaskingStrategy {
    boolean isSecret(String propertyName);

    Object mask(String propertyName, Object value);

    /**
     * Returns {@code true} when the value should be masked, considering both the property name and
     * the value itself. The default implementation only inspects the name; strategies can override
     * to also detect secret-looking values.
     */
    default boolean shouldMask(String propertyName, Object value) {
        return isSecret(propertyName);
    }
}
