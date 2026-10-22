package io.github.jdubois.bootui.quarkus.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.DefaultDatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Quarkus binding of the {@link DatabaseAdvisorEvidenceProvider} SPI. It only says where to find the datasources (through
 * {@link QuarkusDatabaseAdvisorDataSourceProvider}), the optional pool provider and the optional Hibernate observation
 * source; attribution and batching live in the engine's {@link DefaultDatabaseAdvisorEvidenceProvider}.
 *
 * <p>It is constructed directly by the always-produced scanner and imports neither Agroal nor Hibernate ORM: the
 * {@link ConnectionPoolProvider} is produced only with the {@code AGROAL} capability and the
 * {@link HibernateAdvisorObservationSource} only with {@code HIBERNATE_ORM}, each read through an {@link Instance}
 * that is unsatisfied (or ambiguous) when absent, which means no evidence rather than a failure or a guess.</p>
 */
public final class QuarkusDatabaseAdvisorEvidenceProvider implements DatabaseAdvisorEvidenceProvider {

    private final DefaultDatabaseAdvisorEvidenceProvider delegate;

    public QuarkusDatabaseAdvisorEvidenceProvider(
            Instance<DataSource> dataSources,
            BeanManager beanManager,
            Instance<ConnectionPoolProvider> pools,
            Instance<HibernateAdvisorObservationSource> hibernate) {
        QuarkusDatabaseAdvisorDataSourceProvider provider =
                new QuarkusDatabaseAdvisorDataSourceProvider(dataSources, beanManager);
        this.delegate = new DefaultDatabaseAdvisorEvidenceProvider(
                () -> provider.discover().dataSources(), () -> resolved(pools), () -> resolved(hibernate));
    }

    private static <T> T resolved(Instance<T> instance) {
        return instance != null && instance.isResolvable() ? instance.get() : null;
    }

    @Override
    public List<DataSourceConfiguration> dataSourceConfigurations() {
        return delegate.dataSourceConfigurations();
    }

    @Override
    public Map<String, JdbcBatching> jdbcBatching() {
        return delegate.jdbcBatching();
    }
}
