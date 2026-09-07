package io.github.jdubois.bootui.quarkus.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.inject.Instance;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class QuarkusDatabaseAdvisorDataSourceProviderTest {

    @Test
    void absentJdbcExtensionProducesASuccessfullyEmptyInventory() {
        Instance<DataSource> beans = mock();
        when(beans.isUnsatisfied()).thenReturn(true);

        DatabaseAdvisorDataSourceDiscovery discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans).discover();

        assertThat(discovery.dataSources()).isEmpty();
        assertThat(discovery.failures()).isEmpty();
    }

    @Test
    void retainsOtherBeansWhenOneHandleFailsWithoutOpeningConnections() throws SQLException {
        InjectableInstance<DataSource> beans = mock();
        InstanceHandle<DataSource> broken = mock();
        InstanceHandle<DataSource> healthy = mock();
        DataSource pool = mock();
        when(beans.handles()).thenReturn(List.of(broken, healthy));
        when(broken.get()).thenThrow(new IllegalStateException("producer failed"));
        when(healthy.get()).thenReturn(pool);
        QuarkusDatabaseAdvisorDataSourceProvider provider = new QuarkusDatabaseAdvisorDataSourceProvider(beans);

        DatabaseAdvisorDataSourceDiscovery discovery = provider.discover();

        assertThat(discovery.dataSources()).containsExactly(new NamedDataSource("datasource-2", pool));
        assertThat(discovery.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.name()).isEqualTo("default");
            assertThat(failure.message()).contains("producer failed");
        });
        verify(pool, never()).getConnection();
        assertThatThrownBy(provider::dataSources).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deduplicatesPhysicalBeanIdentity() {
        InjectableInstance<DataSource> beans = mock();
        InstanceHandle<DataSource> first = mock();
        InstanceHandle<DataSource> second = mock();
        DataSource pool = mock();
        when(beans.handles()).thenReturn(List.of(first, second));
        when(first.get()).thenReturn(pool);
        when(second.get()).thenReturn(pool);

        assertThat(new QuarkusDatabaseAdvisorDataSourceProvider(beans)
                        .discover()
                        .dataSources())
                .containsExactly(new NamedDataSource("default", pool));
    }

    @Test
    void nullProducerResultIsNotReportedAsAbsent() {
        InjectableInstance<DataSource> beans = mock();
        InstanceHandle<DataSource> handle = mock();
        when(beans.handles()).thenReturn(List.of(handle));

        DatabaseAdvisorDataSourceDiscovery discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans).discover();

        assertThat(discovery.dataSources()).isEmpty();
        assertThat(discovery.failures())
                .singleElement()
                .satisfies(failure -> assertThat(failure.message()).contains("resolved to null"));
    }
}
