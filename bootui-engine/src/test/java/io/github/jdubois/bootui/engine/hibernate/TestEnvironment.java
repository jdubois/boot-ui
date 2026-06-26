package io.github.jdubois.bootui.engine.hibernate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Neutral, framework-free stand-in for the Spring {@code MockEnvironment} used by the Hibernate engine
 * tests. It exposes exactly the two seams the engine consumes — a {@code Function<String, String>}
 * property lookup and a {@code List<String>} of active profiles — so these tests carry no Spring
 * dependency for configuration while keeping the original fluent {@code withProperty} call sites.
 */
final class TestEnvironment {

    private final Map<String, String> properties = new LinkedHashMap<>();
    private List<String> activeProfiles = List.of();

    TestEnvironment withProperty(String key, String value) {
        properties.put(key, value);
        return this;
    }

    void setActiveProfiles(String... profiles) {
        activeProfiles = List.of(profiles);
    }

    Function<String, String> lookup() {
        return properties::get;
    }

    List<String> activeProfiles() {
        return activeProfiles;
    }
}
