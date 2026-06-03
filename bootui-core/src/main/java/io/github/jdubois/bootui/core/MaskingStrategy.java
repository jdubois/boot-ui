package io.github.jdubois.bootui.core;

public interface MaskingStrategy {
    boolean isSecret(String propertyName);
    Object mask(String propertyName, Object value);
}
