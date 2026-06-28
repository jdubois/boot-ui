package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.flyway.QuarkusFlywayProvider;
import io.github.jdubois.bootui.spi.FlywayProvider;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Flyway-panel wiring for the Quarkus adapter: produces the Flyway-API-free {@link FlywayProvider} the engine
 * {@code FlywayService} reads, backed by the application's active Quarkus {@code FlywayContainer} beans.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when the {@code FLYWAY} capability is absent.</strong> The extension runtime jar is
 * Jandex-indexed (so Arc discovers the always-on beans), and Arc treats a {@code @Produces} method as
 * bean-defining — so this producer would be discovered whenever the jar is indexed, and its body references
 * {@link QuarkusFlywayProvider}, which imports {@code org.flywaydb.*} / {@code io.quarkus.flyway.*} types that
 * must stay absent in a Flyway-absent application (R2). The processor therefore actively excludes this class
 * from discovery unless the {@code FLYWAY} capability is present (see
 * {@code BootUiQuarkusProcessor#registerFlyway}). This mirrors {@link BootUiCacheProducer},
 * {@link BootUiHibernateProducer}, {@link BootUiHealthProducer} and {@link BootUiOtelProducer} exactly.</p>
 *
 * <p>When the capability is absent there is no {@code FlywayProvider} bean, so the always-produced
 * {@code FlywayService} (see {@link BootUiEngineProducer}) resolves an unsatisfied {@code Instance} to a
 * {@code null} provider and renders the panel unavailable.</p>
 */
public class BootUiFlywayProducer {

    @Produces
    @Singleton
    public FlywayProvider flywayProvider() {
        return new QuarkusFlywayProvider();
    }
}
