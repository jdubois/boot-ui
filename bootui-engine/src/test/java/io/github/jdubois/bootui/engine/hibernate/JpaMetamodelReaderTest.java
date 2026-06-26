package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link JpaMetamodelReader}, the sole engine class that reads the
 * {@code jakarta.persistence} metamodel. The metamodel walk itself is exercised end-to-end through the
 * sample-app wiring test; here we pin the two fail-soft guards that keep a misbehaving persistence unit
 * from breaking the advisor.
 */
class JpaMetamodelReaderTest {

    @Test
    void readEntitiesWithNoFactoriesReportsUnavailable() {
        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of());

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.repositories()).isEmpty();
        assertThat(discovery.errors()).containsExactly("No EntityManagerFactory beans are available.");
    }

    @Test
    void readEntitiesCapturesMetamodelFailureAsError() {
        EntityManagerFactory failing = throwingFactory("metamodel unavailable");

        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of(failing));

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.errors()).containsExactly("metamodel unavailable");
    }

    private static EntityManagerFactory throwingFactory(String message) {
        return (EntityManagerFactory) Proxy.newProxyInstance(
                JpaMetamodelReaderTest.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> {
                    if ("getMetamodel".equals(method.getName())) {
                        throw new IllegalStateException(message);
                    }
                    if ("toString".equals(method.getName())) {
                        return "ThrowingEntityManagerFactory";
                    }
                    Class<?> returnType = method.getReturnType();
                    return returnType.isPrimitive() && returnType == boolean.class ? Boolean.FALSE : null;
                });
    }
}
