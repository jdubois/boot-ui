package io.github.jdubois.bootui.quarkus.hibernate;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quarkus mapped-entity discovery: reads the JPA metamodel from the application's
 * {@link EntityManagerFactory} beans via the engine {@link JpaMetamodelReader} (the sole
 * {@code jakarta.persistence} reader). The Quarkus analogue of the Spring adapter's
 * {@code SpringHibernateDiscovery#discoverEntities}.
 *
 * <p>Two correctness guards mirror the breadth/de-duplication the Spring {@code ObjectProvider.stream()}
 * path gives for free:</p>
 *
 * <ul>
 *   <li>The producer injects {@code @Any Instance<EntityManagerFactory>} so <em>named</em> and multiple
 *       persistence units are all discovered (a plain {@code @Default} injection would silently miss them).
 *       This class only needs the resulting {@link Iterable}.</li>
 *   <li>Factories are {@linkplain #dedupeByIdentity de-duplicated by identity} before the metamodel is read,
 *       because {@code org.hibernate.SessionFactory} extends {@link EntityManagerFactory}: the same
 *       persistence unit can surface as two distinct beans (the {@code EntityManagerFactory} and the
 *       {@code SessionFactory}) that share one metamodel, which {@link JpaMetamodelReader} — having no
 *       identity de-dup of its own — would otherwise double-count.</li>
 * </ul>
 *
 * <p>Repository discovery is intentionally empty: Quarkus uses Panache rather than Spring Data, so the
 * repository-backed rules are skipped (a deferred follow-up). The whole call is fail-soft: any
 * {@link RuntimeException} or {@link LinkageError} surfaces as an {@link EntityDiscovery} carrying the
 * error message rather than propagating, so the advisor renders a DISABLED report instead of failing.</p>
 */
public final class QuarkusEntityDiscovery {

    private QuarkusEntityDiscovery() {}

    public static EntityDiscovery discover(Iterable<EntityManagerFactory> factories) {
        try {
            return JpaMetamodelReader.readEntities(dedupeByIdentity(factories));
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
    }

    static List<EntityManagerFactory> dedupeByIdentity(Iterable<EntityManagerFactory> factories) {
        Map<EntityManagerFactory, Boolean> seen = new IdentityHashMap<>();
        List<EntityManagerFactory> distinct = new ArrayList<>();
        for (EntityManagerFactory factory : factories) {
            if (factory != null && seen.put(factory, Boolean.TRUE) == null) {
                distinct.add(factory);
            }
        }
        return distinct;
    }
}
