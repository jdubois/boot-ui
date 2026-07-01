package io.github.jdubois.bootui.quarkus.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusEntityDiscoveryTest {

    /** A bare {@link EntityManagerFactory} proxy usable only as an identity key (no metamodel). */
    private static EntityManagerFactory proxyFactory() {
        return (EntityManagerFactory) Proxy.newProxyInstance(
                QuarkusEntityDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "EmfProxy@" + System.identityHashCode(proxy);
                    default -> null;
                });
    }

    @Test
    void dedupesFactoriesByIdentityPreservingEncounterOrder() {
        EntityManagerFactory emf1 = proxyFactory();
        EntityManagerFactory emf2 = proxyFactory();

        // The same persistence unit can surface twice (EntityManagerFactory + SessionFactory share a metamodel);
        // de-dup by identity so JpaMetamodelReader does not double-count entities.
        List<EntityManagerFactory> distinct = QuarkusEntityDiscovery.dedupeByIdentity(List.of(emf1, emf1, emf2));

        assertThat(distinct).containsExactly(emf1, emf2);
    }

    @Test
    void dedupeSkipsNullFactories() {
        EntityManagerFactory emf1 = proxyFactory();

        assertThat(QuarkusEntityDiscovery.dedupeByIdentity(Arrays.asList(emf1, null, emf1)))
                .containsExactly(emf1);
    }

    @Test
    void discoverWithNoFactoriesYieldsEmptyDiscovery() {
        EntityDiscovery discovery = QuarkusEntityDiscovery.discover(List.of());

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.repositories()).isEmpty();
    }
}
