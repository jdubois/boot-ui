package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.datasource.QuarkusAgroalConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Database Connection Pools wiring for the Quarkus adapter: produces the Agroal-API-free
 * {@link ConnectionPoolProvider} the engine {@code ConnectionPoolService} reads, backed by the application's
 * active Agroal datasources.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when the {@code AGROAL} capability is absent.</strong> The extension runtime jar is
 * Jandex-indexed (so Arc discovers the always-on beans), and Arc treats a {@code @Produces} method as
 * bean-defining — so this producer would be discovered whenever the jar is indexed, and the
 * {@link QuarkusAgroalConnectionPoolProvider} it {@code new}-constructs references {@code io.agroal} types that
 * must stay absent in an application without a JDBC datasource extension (R2). The processor therefore actively
 * excludes this class from discovery unless the {@code AGROAL} capability is present (see
 * {@code BootUiQuarkusProcessor#registerConnectionPools}). This mirrors {@link BootUiCacheProducer},
 * {@link BootUiHibernateProducer}, {@link BootUiHealthProducer} and {@link BootUiOtelProducer} exactly.</p>
 *
 * <p>When the capability is absent there is no {@code ConnectionPoolProvider} bean, so the always-produced
 * {@code ConnectionPoolService} (see {@link BootUiEngineProducer}) resolves an unsatisfied {@code Instance} to
 * a {@code null} provider and renders the panel unavailable.</p>
 */
public class BootUiAgroalProducer {

    @Produces
    @Singleton
    public ConnectionPoolProvider connectionPoolProvider() {
        return new QuarkusAgroalConnectionPoolProvider();
    }
}
