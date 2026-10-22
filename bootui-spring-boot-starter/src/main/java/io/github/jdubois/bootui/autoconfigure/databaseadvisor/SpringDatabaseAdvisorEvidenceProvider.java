package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.DefaultDatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring binding of the {@link DatabaseAdvisorEvidenceProvider} SPI. It only says where to find the datasource beans
 * (through {@link SpringDatabaseAdvisorDataSourceProvider}), the optional pool provider and the optional Hibernate
 * observation source; attribution and batching live in the engine's {@link DefaultDatabaseAdvisorEvidenceProvider}.
 *
 * <p>It imports neither HikariCP nor Hibernate ORM: the {@link ConnectionPoolProvider} bean exists only when HikariCP is
 * present and the {@link HibernateAdvisorObservationSource} bean only when JPA and Hibernate are, and each is reached
 * through an {@link ObjectProvider} so an absent or ambiguous bean means no evidence, never a failure or a guess.</p>
 */
public final class SpringDatabaseAdvisorEvidenceProvider implements DatabaseAdvisorEvidenceProvider {

    private final DefaultDatabaseAdvisorEvidenceProvider delegate;

    public SpringDatabaseAdvisorEvidenceProvider(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<ConnectionPoolProvider> pools,
            ObjectProvider<HibernateAdvisorObservationSource> hibernate) {
        SpringDatabaseAdvisorDataSourceProvider dataSources =
                new SpringDatabaseAdvisorDataSourceProvider(beanFactoryProvider);
        this.delegate = new DefaultDatabaseAdvisorEvidenceProvider(
                () -> dataSources.discover().dataSources(), pools::getIfUnique, hibernate::getIfUnique);
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
