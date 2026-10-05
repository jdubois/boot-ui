package io.github.jdubois.bootui.autoconfigure.liquibase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * End-to-end tests for the Liquibase migration action, the only BootUI panel operation that mutates a
 * developer's database schema.
 *
 * <p>{@link SpringLiquibaseProviderTests} stubs the action executor to prove the provider selects the right
 * bean; these tests run the real executor against an in-memory H2 database, so the change sets are actually
 * applied. That covers the parts a stub cannot: the reported before/after/applied counts, the applied and
 * pending change-log reads that back the panel, that a second run is a no-op, and — most importantly — that
 * BootUI never inherits a destructive {@code dropFirst}/{@code clearCheckSums} setting from the application's
 * own {@link SpringLiquibase} bean when it runs an update on the user's behalf.</p>
 */
class SpringLiquibaseUpdateTests {

    private static final String CHANGELOG = "classpath:/db/bootui-liquibase-test-changelog.sql";

    private JdbcDataSource dataSource;

    @BeforeEach
    void createIsolatedDatabase() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:bootui-liquibase-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
    }

    private SpringLiquibase liquibaseBean(String changeLog) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        // BootUI must never run the application's bean itself, so it is never started here either.
        liquibase.setShouldRun(false);
        return liquibase;
    }

    @SuppressWarnings("unchecked")
    private SpringLiquibaseProvider providerFor(String beanName, SpringLiquibase liquibase) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(eq(beanName), eq(SpringLiquibase.class))).thenReturn(liquibase);
        ObjectProvider<ListableBeanFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        return new SpringLiquibaseProvider(provider);
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return tables.next();
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void updateAppliesEveryPendingChangeSetAndReportsTheCounts() throws Exception {
        SpringLiquibaseProvider provider = providerFor("liquibase", liquibaseBean(CHANGELOG));

        LiquibaseActionResult result = provider.update("liquibase");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.beanName()).isEqualTo("liquibase");
        assertThat(result.pendingBefore()).isEqualTo(2);
        assertThat(result.pendingAfter()).isZero();
        assertThat(result.changeSetsApplied()).isEqualTo(2);
        assertThat(result.message()).isEqualTo("Liquibase applied 2 change set(s).");
        assertThat(result.warnings()).isEmpty();
        assertThat(tableExists("bootui_widget")).isTrue();
        assertThat(tableExists("bootui_gadget")).isTrue();
    }

    @Test
    void aSecondUpdateIsANoOpInsteadOfReapplyingChangeSets() throws Exception {
        SpringLiquibaseProvider provider = providerFor("liquibase", liquibaseBean(CHANGELOG));
        provider.update("liquibase");

        LiquibaseActionResult result = provider.update("liquibase");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.pendingBefore()).isZero();
        assertThat(result.pendingAfter()).isZero();
        assertThat(result.changeSetsApplied()).isZero();
        assertThat(result.message()).isEqualTo("Liquibase database is already up to date.");
    }

    @Test
    void pendingChangeSetsAreReadBeforeTheUpdateAndAppliedOnesAfterwards() throws Exception {
        SpringLiquibase liquibase = liquibaseBean(CHANGELOG);
        SpringLiquibaseProvider provider = providerFor("dataLiquibase", liquibase);

        LiquibaseDatabaseSnapshot before = provider.databases().get(0);
        assertThat(before.name()).isEqualTo("dataLiquibase");
        assertThat(before.updateDisabledReason()).isNull();
        assertThat(before.appliedChangeSets()).isEmpty();
        assertThat(before.pendingChangeSets())
                .extracting(LiquibaseChangeSetDto::id)
                .containsExactly("create-widget", "create-gadget");
        assertThat(before.pendingChangeSets())
                .extracting(LiquibaseChangeSetDto::execType)
                .containsOnly("PENDING");

        provider.update("dataLiquibase");

        LiquibaseDatabaseSnapshot after = provider.databases().get(0);
        assertThat(after.pendingChangeSets()).isEmpty();
        assertThat(after.appliedChangeSets())
                .extracting(LiquibaseChangeSetDto::id)
                .containsExactly("create-widget", "create-gadget");
        LiquibaseChangeSetDto applied = after.appliedChangeSets().get(0);
        assertThat(applied.author()).isEqualTo("bootui");
        assertThat(applied.execType()).isEqualTo("EXECUTED");
        assertThat(applied.dateExecuted()).isNotNull();
        assertThat(applied.checksum()).isNotBlank();
        assertThat(applied.orderExecuted()).isNotNull();
    }

    @Test
    void changeLogParametersDeclaredOnTheApplicationBeanAreHonoured() throws Exception {
        SpringLiquibase liquibase = liquibaseBean("classpath:/db/bootui-liquibase-parameterized-changelog.sql");
        liquibase.setChangeLogParameters(Map.of("bootui.test.table", "bootui_parameterized"));
        SpringLiquibaseProvider provider = providerFor("liquibase", liquibase);

        LiquibaseActionResult result = provider.update("liquibase");

        assertThat(result.changeSetsApplied()).isEqualTo(1);
        assertThat(tableExists("bootui_parameterized")).isTrue();
    }

    @Test
    void updateNeverInheritsADestructiveDropFirstFromTheApplicationBean() throws Exception {
        execute("CREATE TABLE pre_existing (id INT PRIMARY KEY)");
        execute("INSERT INTO pre_existing (id) VALUES (1)");
        SpringLiquibase liquibase = liquibaseBean(CHANGELOG);
        liquibase.setDropFirst(true);
        liquibase.setClearCheckSums(true);
        SpringLiquibaseProvider provider = providerFor("liquibase", liquibase);

        LiquibaseActionResult result = provider.update("liquibase");

        assertThat(result.changeSetsApplied()).isEqualTo(2);
        assertThat(tableExists("pre_existing")).isTrue();
        assertThat(countRows("pre_existing")).isEqualTo(1);
    }

    @Test
    void updateOnlyTouchesTheSelectedBeansDatabase() throws Exception {
        JdbcDataSource otherDataSource = new JdbcDataSource();
        otherDataSource.setURL("jdbc:h2:mem:bootui-liquibase-other-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        otherDataSource.setUser("sa");
        SpringLiquibase selected = liquibaseBean(CHANGELOG);
        SpringLiquibase other = new SpringLiquibase();
        other.setDataSource(otherDataSource);
        other.setChangeLog(CHANGELOG);
        other.setResourceLoader(new DefaultResourceLoader());
        other.setShouldRun(false);

        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[] {"selected", "other"});
        when(factory.getBean(eq("selected"), eq(SpringLiquibase.class))).thenReturn(selected);
        when(factory.getBean(eq("other"), eq(SpringLiquibase.class))).thenReturn(other);
        @SuppressWarnings("unchecked")
        ObjectProvider<ListableBeanFactory> beanFactoryProvider = mock(ObjectProvider.class);
        when(beanFactoryProvider.getIfAvailable()).thenReturn(factory);
        SpringLiquibaseProvider provider = new SpringLiquibaseProvider(beanFactoryProvider);

        provider.update("selected");

        assertThat(tableExists("bootui_widget")).isTrue();
        try (Connection connection = otherDataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, null, "BOOTUI_WIDGET", null)) {
            assertThat(tables.next()).isFalse();
        }
    }

    @Test
    void updateFailsClosedWhenTheDataSourceCannotConnect() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(broken);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        SpringLiquibaseProvider provider = providerFor("liquibase", liquibase);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.update("liquibase"))
                .isInstanceOf(Exception.class);
        assertThat(provider.databases())
                .extracting(LiquibaseDatabaseSnapshot::appliedChangeSets)
                .containsExactly(List.of());
    }
}
