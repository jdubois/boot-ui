package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.hibernate.EntityDiscoverySource;
import io.github.jdubois.bootui.quarkus.hibernate.QuarkusEntityDiscovery;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManagerFactory;

/**
 * Hibernate ORM entity-discovery wiring for the Quarkus adapter: produces the JPA-free
 * {@link EntityDiscoverySource} the engine {@code HibernateScanner} reads, backed by the application's
 * {@link EntityManagerFactory} beans.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when Hibernate ORM is absent.</strong> The extension runtime jar is Jandex-indexed (so Arc
 * discovers the always-on beans), and Arc treats a {@code @Produces} method as bean-defining — so this producer
 * is discovered whenever the jar is indexed, and Arc would try to resolve its {@link EntityManagerFactory}
 * parameter type even in an application without {@code quarkus-hibernate-orm}, linking a {@code jakarta.persistence}
 * type that must stay absent (R2). The processor therefore actively excludes this class from discovery unless the
 * {@code HIBERNATE_ORM} capability is present (see {@code BootUiQuarkusProcessor#registerHibernateAdvisor}). This
 * mirrors {@link BootUiHealthProducer} and {@link BootUiOtelProducer} exactly.</p>
 *
 * <p>The factories are injected as {@code @Any Instance<EntityManagerFactory>} so named/multiple persistence
 * units are all discovered, and resolved <em>live</em> on every scan through {@link QuarkusEntityDiscovery}
 * (which de-duplicates them by identity). When Hibernate ORM is absent there is no {@code EntityDiscoverySource}
 * bean, so the always-produced {@code HibernateScanner} (see {@link BootUiEngineProducer}) is given a supplier
 * that yields an empty discovery and renders the panel as not-configured.</p>
 */
public class BootUiHibernateProducer {

    @Produces
    @Singleton
    public EntityDiscoverySource hibernateEntityDiscoverySource(
            @Any Instance<EntityManagerFactory> entityManagerFactories) {
        return () -> QuarkusEntityDiscovery.discover(entityManagerFactories);
    }
}
