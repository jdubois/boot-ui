package io.github.jdubois.bootui.quarkus.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
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

    @Test
    void namedPoolsSurviveTheDefaultSqlTraceAlternativesPriorityWithoutBorrowingConnections() throws SQLException {
        InjectableInstance<DataSource> beans = mock();
        InjectableInstance<DataSource> namedSelection = mock();
        BeanManager manager = mock();
        InjectableBean<DataSource> namedBean = mock();
        InstanceHandle<DataSource> defaultHandle = mock();
        InstanceHandle<DataSource> namedHandle = mock();
        DataSource defaultPool = mock();
        DataSource namedPool = mock();
        var qualifier = new io.quarkus.agroal.DataSource.DataSourceLiteral("mysql");
        when(beans.handles()).thenReturn(List.of(defaultHandle));
        when(defaultHandle.get()).thenReturn(defaultPool);
        when(manager.getBeans(DataSource.class, Any.Literal.INSTANCE)).thenReturn(Set.of(namedBean));
        when(namedBean.getQualifiers()).thenReturn(Set.of(qualifier));
        when(beans.select(qualifier)).thenReturn(namedSelection);
        when(namedSelection.handles()).thenReturn(List.of(namedHandle));
        when(namedHandle.getBean()).thenReturn(namedBean);
        when(namedHandle.get()).thenReturn(namedPool);

        var discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans, manager).discover();

        assertThat(discovery.dataSources())
                .containsExactly(new NamedDataSource("default", defaultPool), new NamedDataSource("mysql", namedPool));
        assertThat(discovery.failures()).isEmpty();
        verify(defaultPool, never()).getConnection();
        verify(namedPool, never()).getConnection();
    }

    /**
     * With SQL Trace off, the unqualified pass already sees every named pool, and the qualifier pass then
     * selects the same beans again. Resolving them twice invoked the producer twice — which a
     * {@code @Dependent} producer answers with a second pool that identity de-duplication can no longer
     * collapse — so each bean is now resolved exactly once, by Arc identifier.
     */
    @Test
    void resolvesEachBeanOnceWhenTheQualifierPassReselectsAnAlreadyVisitedNamedPool() {
        InjectableInstance<DataSource> beans = mock();
        InjectableInstance<DataSource> namedSelection = mock();
        BeanManager manager = mock();
        InjectableBean<DataSource> defaultBean = mock();
        InjectableBean<DataSource> namedBean = mock();
        InstanceHandle<DataSource> defaultHandle = mock();
        InstanceHandle<DataSource> namedHandle = mock();
        InstanceHandle<DataSource> reselectedHandle = mock();
        DataSource defaultPool = mock();
        var qualifier = new io.quarkus.agroal.DataSource.DataSourceLiteral("reporting");
        when(defaultBean.getIdentifier()).thenReturn("bean-default");
        when(namedBean.getIdentifier()).thenReturn("bean-reporting");
        when(namedBean.getQualifiers()).thenReturn(Set.of(qualifier));
        when(defaultHandle.getBean()).thenReturn(defaultBean);
        when(defaultHandle.get()).thenReturn(defaultPool);
        when(namedHandle.getBean()).thenReturn(namedBean);
        // A dependent producer answers every resolution with a fresh pool, so a repeated pass would be visible.
        when(namedHandle.get()).thenAnswer(invocation -> mock(DataSource.class));
        when(reselectedHandle.getBean()).thenReturn(namedBean);
        when(reselectedHandle.get()).thenAnswer(invocation -> mock(DataSource.class));
        when(beans.handles()).thenReturn(List.of(defaultHandle, namedHandle));
        when(manager.getBeans(DataSource.class, Any.Literal.INSTANCE)).thenReturn(Set.of(defaultBean, namedBean));
        when(beans.select(qualifier)).thenReturn(namedSelection);
        when(namedSelection.handles()).thenReturn(List.of(reselectedHandle));

        var discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans, manager).discover();

        assertThat(discovery.dataSources()).extracting(NamedDataSource::name).containsExactly("default", "reporting");
        assertThat(discovery.failures()).isEmpty();
        verify(namedHandle).get();
        verify(reselectedHandle, never()).get();
    }

    /** A producer that keeps failing must be reported once, under one name, not once per resolution pass. */
    @Test
    void reportsARepeatedlySelectedFailingBeanExactlyOnce() {
        InjectableInstance<DataSource> beans = mock();
        InjectableInstance<DataSource> namedSelection = mock();
        BeanManager manager = mock();
        InjectableBean<DataSource> brokenBean = mock();
        InstanceHandle<DataSource> brokenHandle = mock();
        InstanceHandle<DataSource> reselectedHandle = mock();
        var qualifier = new io.quarkus.agroal.DataSource.DataSourceLiteral("reporting");
        when(brokenBean.getIdentifier()).thenReturn("bean-reporting");
        when(brokenBean.getQualifiers()).thenReturn(Set.of(qualifier));
        when(brokenHandle.getBean()).thenReturn(brokenBean);
        when(brokenHandle.get()).thenThrow(new IllegalStateException("producer failed"));
        when(reselectedHandle.getBean()).thenReturn(brokenBean);
        when(reselectedHandle.get()).thenThrow(new IllegalStateException("producer failed"));
        when(beans.handles()).thenReturn(List.of(brokenHandle));
        when(manager.getBeans(DataSource.class, Any.Literal.INSTANCE)).thenReturn(Set.of(brokenBean));
        when(beans.select(qualifier)).thenReturn(namedSelection);
        when(namedSelection.handles()).thenReturn(List.of(reselectedHandle));

        var discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans, manager).discover();

        assertThat(discovery.dataSources()).isEmpty();
        assertThat(discovery.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.name()).isEqualTo("reporting");
            assertThat(failure.message()).contains("producer failed");
        });
        verify(reselectedHandle, never()).get();
    }

    /**
     * A bean the qualifier pass reaches first still continues the positional numbering instead of restarting at
     * {@code default}, so two distinct beans can never be published under the same arbitrary name.
     */
    @Test
    void positionalNamingContinuesAcrossPassesForAQualifierThatCarriesNoName() {
        InjectableInstance<DataSource> beans = mock();
        InjectableInstance<DataSource> namedSelection = mock();
        BeanManager manager = mock();
        InjectableBean<DataSource> anonymousBean = mock();
        InjectableBean<DataSource> tracedBean = mock();
        InstanceHandle<DataSource> anonymousHandle = mock();
        InstanceHandle<DataSource> tracedHandle = mock();
        DataSource tracedPool = mock();
        DataSource otherPool = mock();
        var qualifier = new io.quarkus.agroal.DataSource.DataSourceLiteral("");
        when(tracedBean.getIdentifier()).thenReturn("bean-traced");
        when(tracedHandle.getBean()).thenReturn(tracedBean);
        when(tracedHandle.get()).thenReturn(tracedPool);
        when(anonymousBean.getIdentifier()).thenReturn("bean-anonymous");
        when(anonymousBean.getQualifiers()).thenReturn(Set.of(qualifier));
        when(anonymousHandle.getBean()).thenReturn(anonymousBean);
        when(anonymousHandle.get()).thenReturn(otherPool);
        when(beans.handles()).thenReturn(List.of(tracedHandle));
        when(manager.getBeans(DataSource.class, Any.Literal.INSTANCE)).thenReturn(Set.of(anonymousBean));
        when(beans.select(qualifier)).thenReturn(namedSelection);
        when(namedSelection.handles()).thenReturn(List.of(anonymousHandle));

        var discovery = new QuarkusDatabaseAdvisorDataSourceProvider(beans, manager).discover();

        assertThat(discovery.dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("default", "datasource-2");
    }
}
