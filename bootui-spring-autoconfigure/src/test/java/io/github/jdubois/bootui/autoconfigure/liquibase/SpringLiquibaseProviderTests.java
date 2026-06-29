package io.github.jdubois.bootui.autoconfigure.liquibase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Behavioural tests for the Spring-specific {@link SpringLiquibaseProvider} discovery and reads.
 *
 * <p>Reproduces the former {@code LiquibaseController} test coverage: empty results when no bean factory or no
 * Liquibase beans are present, fail-closed reads when a bean's change-log history table is unreadable, the
 * update-disabled reasons, and that the action primitive runs only the selected bean.</p>
 */
class SpringLiquibaseProviderTests {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static ListableBeanFactory factoryWithLiquibase(String beanName, SpringLiquibase liquibase) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(eq(beanName), eq(SpringLiquibase.class))).thenReturn(liquibase);
        return factory;
    }

    @Test
    void availableIsAlwaysTrueOnSpring() {
        SpringLiquibaseProvider provider = new SpringLiquibaseProvider(providerOf(null));

        assertThat(provider.available()).isTrue();
    }

    @Test
    void databasesIsEmptyWhenNoBeanFactory() {
        SpringLiquibaseProvider provider = new SpringLiquibaseProvider(providerOf(null));

        assertThat(provider.databases()).isEmpty();
        assertThat(provider.targets()).isEmpty();
    }

    @Test
    void databasesIsEmptyWhenNoLiquibaseBeans() {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[0]);
        SpringLiquibaseProvider provider = new SpringLiquibaseProvider(providerOf(factory));

        assertThat(provider.databases()).isEmpty();
    }

    @Test
    void databasesFailClosedWhenHistoryTableIsUnreadable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        SpringLiquibase liquibase = mock(SpringLiquibase.class);
        when(liquibase.getDataSource()).thenReturn(dataSource);
        SpringLiquibaseProvider provider =
                new SpringLiquibaseProvider(providerOf(factoryWithLiquibase("liquibase", liquibase)));

        List<LiquibaseDatabaseSnapshot> databases = provider.databases();

        assertThat(databases).hasSize(1);
        LiquibaseDatabaseSnapshot snapshot = databases.get(0);
        assertThat(snapshot.name()).isEqualTo("liquibase");
        assertThat(snapshot.appliedChangeSets()).isEmpty();
        assertThat(snapshot.pendingChangeSets()).isEmpty();
        assertThat(snapshot.updateDisabledReason())
                .isEqualTo("Liquibase update cannot run because this bean has no change log.");
    }

    @Test
    void targetsReportDisabledReasonWhenNoDataSource() {
        SpringLiquibase liquibase = new SpringLiquibase();
        SpringLiquibaseProvider provider =
                new SpringLiquibaseProvider(providerOf(factoryWithLiquibase("liquibase", liquibase)));

        List<LiquibaseTarget> targets = provider.targets();

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).name()).isEqualTo("liquibase");
        assertThat(targets.get(0).updateDisabledReason())
                .isEqualTo("Liquibase update cannot run because this bean has no DataSource.");
    }

    @Test
    void targetsReportDisabledReasonWhenNoChangeLog() {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(mock(DataSource.class));
        SpringLiquibaseProvider provider =
                new SpringLiquibaseProvider(providerOf(factoryWithLiquibase("liquibase", liquibase)));

        assertThat(provider.targets().get(0).updateDisabledReason())
                .isEqualTo("Liquibase update cannot run because this bean has no change log.");
    }

    @Test
    void updateRunsTheSelectedBean() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(mock(DataSource.class));
        liquibase.setChangeLog("classpath:/db/changelog.xml");
        SpringLiquibaseProvider.LiquibaseActionExecutor executor =
                mock(SpringLiquibaseProvider.LiquibaseActionExecutor.class);
        when(executor.update(eq("inventoryLiquibase"), eq(liquibase)))
                .thenReturn(new LiquibaseActionResult(
                        "success", "Liquibase applied 2 change set(s).", "inventoryLiquibase", 3, 1, 2, List.of()));
        SpringLiquibaseProvider provider = new SpringLiquibaseProvider(
                providerOf(factoryWithLiquibase("inventoryLiquibase", liquibase)), executor);

        LiquibaseActionResult result = provider.update("inventoryLiquibase");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.changeSetsApplied()).isEqualTo(2);
        verify(executor).update(eq("inventoryLiquibase"), eq(liquibase));
    }

    @Test
    void updateThrowsWhenNoBeanMatchesTheName() {
        SpringLiquibase liquibase = new SpringLiquibase();
        SpringLiquibaseProvider.LiquibaseActionExecutor executor =
                mock(SpringLiquibaseProvider.LiquibaseActionExecutor.class);
        SpringLiquibaseProvider provider =
                new SpringLiquibaseProvider(providerOf(factoryWithLiquibase("liquibase", liquibase)), executor);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.update("missing"))
                .hasMessageContaining("missing");
    }
}
