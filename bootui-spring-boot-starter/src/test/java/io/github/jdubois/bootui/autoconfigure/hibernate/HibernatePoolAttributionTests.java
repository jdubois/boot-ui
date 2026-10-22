package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolAttribution;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorBuilder;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.junit.jupiter.api.Test;

/** The effective provider contract, transaction mode and unit-to-pool attribution read from a live factory. */
class HibernatePoolAttributionTests {

    @Test
    void readsTheEffectiveProviderContractAndTransactionMode() {
        for (boolean jta : new boolean[] {false, true}) {
            SessionFactoryImplementor factory = SpringHibernateAdvisorObservationSourceTests.factory("orders", 25);
            when(factory.getSessionFactoryOptions().doesConnectionProviderDisableAutoCommit())
                    .thenReturn(true);
            transactionCoordinator(factory, jta);
            List<HibernateObservationDiagnostic> diagnostics = new ArrayList<>();

            HibernateFactorySettings settings = HibernateFactorySettingsReader.read(factory, "orders", diagnostics);

            assertThat(settings.providerDisablesAutoCommit()).isTrue();
            assertThat(settings.transactionMode())
                    .isEqualTo(
                            jta
                                    ? HibernateFactorySettings.TransactionMode.JTA
                                    : HibernateFactorySettings.TransactionMode.RESOURCE_LOCAL);
            assertThat(diagnostics).isEmpty();
        }
    }

    @Test
    void aMissingAccessorOrServiceIsUnknownWithADiagnosticNeverAFalseDefault() {
        SessionFactoryImplementor factory = SpringHibernateAdvisorObservationSourceTests.factory("orders", 25);
        when(factory.getSessionFactoryOptions()).thenReturn(null);
        List<HibernateObservationDiagnostic> diagnostics = new ArrayList<>();

        HibernateFactorySettings settings = HibernateFactorySettingsReader.read(factory, "orders", diagnostics);

        assertThat(settings.providerDisablesAutoCommit()).isNull();
        assertThat(settings.transactionMode()).isEqualTo(HibernateFactorySettings.TransactionMode.UNKNOWN);
        assertThat(diagnostics)
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);

        SessionFactoryImplementor failing = SpringHibernateAdvisorObservationSourceTests.factory("orders", 25);
        when(failing.getSessionFactoryOptions().doesConnectionProviderDisableAutoCommit())
                .thenThrow(new IllegalStateException("credential"));
        ServiceRegistryImplementor registry = failing.getServiceRegistry();
        TransactionCoordinatorBuilder builder = mock(TransactionCoordinatorBuilder.class);
        when(builder.isJta()).thenThrow(new IllegalStateException("credential"));
        when(registry.getService(TransactionCoordinatorBuilder.class)).thenReturn(builder);

        HibernateFactorySettings failed = HibernateFactorySettingsReader.read(failing, "orders", new ArrayList<>());

        assertThat(failed.providerDisablesAutoCommit()).isNull();
        assertThat(failed.transactionMode()).isEqualTo(HibernateFactorySettings.TransactionMode.UNKNOWN);
    }

    @Test
    void attributesAUnitToItsPoolThroughTheConnectionProviderWithoutBorrowingAConnection() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        SessionFactoryImplementor factory = factoryBackedBy(dataSource);
        ConnectionPoolProvider pools = mock(ConnectionPoolProvider.class);
        when(pools.poolFor(dataSource)).thenReturn(Optional.of(info("billing", false)));

        HibernatePoolObservation pool = HibernatePoolAttribution.observe(factory, "orders", pools, new ArrayList<>());

        assertThat(pool).isEqualTo(new HibernatePoolObservation("billing", "HikariCP", false));
        verify(dataSource, never()).getConnection();
        verify(dataSource, never()).getConnection(any(), any());
    }

    @Test
    void neverGuessesWhenAnythingIsMissingOrFailing() {
        DataSource dataSource = mock(DataSource.class);
        SessionFactoryImplementor backed = factoryBackedBy(dataSource);
        ConnectionPoolProvider unmatched = mock(ConnectionPoolProvider.class);
        when(unmatched.poolFor(dataSource)).thenReturn(Optional.empty());
        ConnectionPoolProvider failing = mock(ConnectionPoolProvider.class);
        when(failing.poolFor(dataSource)).thenThrow(new IllegalStateException("credential"));

        assertThat(HibernatePoolAttribution.observe(backed, "orders", null, new ArrayList<>()))
                .as("no pool backend")
                .isNull();
        assertThat(HibernatePoolAttribution.observe(backed, "orders", unmatched, new ArrayList<>()))
                .as("no match or several matches")
                .isNull();
        assertThat(HibernatePoolAttribution.observe(backed, "orders", failing, new ArrayList<>()))
                .as("failing lookup")
                .isNull();

        SessionFactoryImplementor builtIn = SpringHibernateAdvisorObservationSourceTests.factory("orders", 25);
        assertThat(HibernatePoolAttribution.observe(builtIn, "orders", failing, new ArrayList<>()))
                .as("a provider that is not backed by a DataSource")
                .isNull();
    }

    static ConnectionPoolInfo info(String name, Boolean autoCommit) {
        return new ConnectionPoolInfo(
                name,
                name,
                ConnectionPoolInfo.HIKARI,
                "jdbc:secret",
                "user",
                null,
                1,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                autoCommit,
                true,
                null,
                null);
    }

    static SessionFactoryImplementor factoryBackedBy(DataSource dataSource) {
        SessionFactoryImplementor factory = SpringHibernateAdvisorObservationSourceTests.factory("orders", 25);
        ConnectionProvider provider = mock(ConnectionProvider.class);
        when(provider.isUnwrappableAs(DataSource.class)).thenReturn(true);
        when(provider.unwrap(DataSource.class)).thenReturn(dataSource);
        when(factory.getServiceRegistry().getService(ConnectionProvider.class)).thenReturn(provider);
        return factory;
    }

    static void transactionCoordinator(SessionFactoryImplementor factory, boolean jta) {
        TransactionCoordinatorBuilder builder = mock(TransactionCoordinatorBuilder.class);
        when(builder.isJta()).thenReturn(jta);
        when(factory.getServiceRegistry().getService(TransactionCoordinatorBuilder.class))
                .thenReturn(builder);
    }
}
