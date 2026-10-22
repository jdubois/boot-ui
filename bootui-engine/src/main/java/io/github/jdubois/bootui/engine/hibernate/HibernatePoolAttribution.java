package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Attributes one persistence unit to the connection pool behind its Hibernate connection provider. It never
 * acquires a connection and never guesses: a missing provider, a provider not backed by a {@code DataSource}, a
 * pool the {@link ConnectionPoolProvider} cannot match, or a lookup that fails all yield {@code null}.
 */
public final class HibernatePoolAttribution {
    private HibernatePoolAttribution() {}

    /**
     * The pool behind {@code factory}'s connection provider, reduced to the fields the Hibernate advisor states, or
     * {@code null} when it could not be attributed. {@code pools} may be {@code null} when the application has no
     * pool backend at all.
     */
    public static HibernatePoolObservation observe(
            Object factory,
            String label,
            ConnectionPoolProvider pools,
            List<HibernateObservationDiagnostic> diagnostics) {
        if (pools == null) return null;
        DataSource dataSource = HibernateFactorySettingsReader.connectionDataSource(factory, label, diagnostics);
        if (dataSource == null) return null;
        try {
            Optional<ConnectionPoolInfo> pool = pools.poolFor(dataSource);
            return pool == null || pool.isEmpty()
                    ? null
                    : new HibernatePoolObservation(
                            pool.get().beanName(),
                            pool.get().implementation(),
                            pool.get().autoCommit());
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
