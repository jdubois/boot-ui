package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.liquibase.QuarkusLiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Liquibase-panel wiring for the Quarkus adapter: produces the liquibase-API-free {@link LiquibaseProvider}
 * the engine {@code LiquibaseService} reads, backed by the {@code quarkus-liquibase} extension's
 * {@code LiquibaseFactory} beans (discovered internally by {@link QuarkusLiquibaseProvider} through
 * {@code LiquibaseFactoryUtil}).
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when the {@code LIQUIBASE} capability is absent.</strong> The extension runtime jar is
 * Jandex-indexed (so Arc discovers the always-on beans), and Arc treats a {@code @Produces} method as
 * bean-defining — so this producer would be discovered whenever the jar is indexed, and the
 * {@link QuarkusLiquibaseProvider} it instantiates statically references {@code io.quarkus.liquibase} /
 * {@code liquibase} types that must stay absent in a Liquibase-absent application (R2). The processor therefore
 * actively excludes this class from discovery unless the {@code LIQUIBASE} capability is present (see
 * {@code BootUiQuarkusProcessor#registerLiquibase}). This mirrors {@link BootUiCacheProducer} and
 * {@link BootUiHibernateProducer} exactly.</p>
 *
 * <p>When the capability is absent there is no {@code LiquibaseProvider} bean, so the always-produced
 * {@code LiquibaseService} (see {@link BootUiEngineProducer}) resolves an unsatisfied {@code Instance} to a
 * {@code null} provider and renders the panel unavailable.</p>
 */
public class BootUiLiquibaseProducer {

    @Produces
    @Singleton
    public LiquibaseProvider liquibaseProvider() {
        return new QuarkusLiquibaseProvider();
    }
}
