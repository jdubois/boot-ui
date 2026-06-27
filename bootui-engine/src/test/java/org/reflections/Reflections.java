package org.reflections;

import java.util.Set;

/**
 * Minimal test stub standing in for the absent Reflections library, so GRAAL-SCAN-001 fixtures can
 * compile and exercise the constructor-based-scan detection. Not the real library.
 */
public class Reflections {

    public Reflections(String basePackage) {}

    public Set<Class<?>> getSubTypesOf(Class<?> type) {
        return Set.of();
    }
}
