package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * End-to-end reproduction of issue #924 against a real Spring Boot context.
 *
 * <p>With {@code spring.datasource.connection-fetch=lazy}, Spring Boot's own bean post-processor replaces the
 * {@code dataSource} bean with a {@link LazyConnectionDataSourceProxy} and the Hikari pool inside it is no longer
 * a bean at all. BootUI used to skip every Spring wrapper on the assumption that it forwards to another
 * {@code DataSource} bean, so the Database Advisor reported "No DataSource beans were found to inspect." while
 * the Connection Pools panel — which unwraps — happily showed the pool. This test pins all three behaviours
 * together: the advisor scans, SQL Trace still wraps the pool, and pool discovery keeps working.</p>
 */
class LazyConnectionDataSourceAdvisorTests {

    private final WebApplicationContextRunner defaultRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class, DataSourceAutoConfiguration.class))
            .withPropertyValues(
                    "bootui.enabled=ON",
                    "bootui.sql-trace.enabled=true",
                    "spring.datasource.url=jdbc:h2:mem:bootui-issue-924;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    private final WebApplicationContextRunner runner =
            defaultRunner.withPropertyValues("spring.datasource.connection-fetch=lazy");

    @Test
    void defaultHikariAndSqlTraceContextEarnsAKnownForeignKeyAccessPath() {
        defaultRunner
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:bootui-default-index-" + System.nanoTime())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource dataSource = context.getBean(DataSource.class);
                    assertThat(dataSource)
                            .isNotInstanceOf(LazyConnectionDataSourceProxy.class)
                            .isInstanceOf(SqlTracedDataSource.class);
                    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
                    assertThat(HikariDataSourceDiscovery.discover(context.getBeanFactory()))
                            .singleElement()
                            .satisfies(entry -> {
                                assertThat(entry.beanName()).isEqualTo("dataSource");
                                assertThat(entry.dataSource()).isSameAs(hikari);
                            });

                    try (Connection connection = dataSource.getConnection();
                            Statement statement = connection.createStatement()) {
                        statement.execute("create table parent (id int primary key)");
                        statement.execute("create table child (id int primary key, parent_id int)");
                        statement.execute("create index ix_child_parent on child(parent_id)");
                        statement.execute("alter table child add constraint fk_child_parent "
                                + "foreign key(parent_id) references parent(id)");
                        statement.execute("SELECT 1");
                        try (ResultSet keys =
                                connection.getMetaData().getImportedKeys(connection.getCatalog(), "PUBLIC", "CHILD")) {
                            assertThat(keys.next()).isTrue();
                            assertThat(keys.getString("FK_NAME")).isEqualTo("FK_CHILD_PARENT");
                            assertThat(keys.getString("FKCOLUMN_NAME")).isEqualTo("PARENT_ID");
                            assertThat(keys.next()).isFalse();
                        }
                        boolean indexObserved = false;
                        try (ResultSet indexes = connection
                                .getMetaData()
                                .getIndexInfo(connection.getCatalog(), "PUBLIC", "CHILD", false, true)) {
                            while (indexes.next()) {
                                if ("IX_CHILD_PARENT".equals(indexes.getString("INDEX_NAME"))) {
                                    indexObserved = true;
                                    assertThat(indexes.getString("COLUMN_NAME")).isEqualTo("PARENT_ID");
                                    assertThat(indexes.getInt("TYPE")).isEqualTo(DatabaseMetaData.tableIndexOther);
                                }
                            }
                        }
                        assertThat(indexObserved).isTrue();
                    }

                    var report =
                            context.getBean(DatabaseAdvisorController.class).scan();

                    assertThat(report.scan().status()).isIn("SCANNED", "PARTIAL");
                    assertThat(report.dataSourceNames()).containsExactly("dataSource");
                    assertThat(report.tablesAnalyzed()).isGreaterThanOrEqualTo(2);
                    assertThat(report.rulesEvaluated()).isPositive();
                    // Results contain findings; every SKIPPED/ERROR/unknown evaluation emits a diagnostic.
                    // With an observed FK, omitting the rule from both establishes PASS, not a no-target skip.
                    assertThat(report.diagnostics())
                            .as("DB-SCHEMA-002 must be assessed, not skipped, errored or unknown")
                            .noneMatch(diagnostic -> diagnostic.source().equals("DB-SCHEMA-002"));
                    assertThat(report.results())
                            .as("the known ordinary covering index must pass DB-SCHEMA-002")
                            .noneMatch(result -> result.id().equals("DB-SCHEMA-002"));
                    assertThat(report.diagnostics())
                            .noneMatch(diagnostic ->
                                    diagnostic.message().contains("verifiable physical H2 connection is unavailable"));
                    SqlTraceRecorder recorder = context.getBean(SqlTraceRecorder.class);
                    assertThat(recorder.dataSourceNames()).contains("dataSource");
                    assertThat(recorder.recent())
                            .extracting(SqlTraceRecorder.CapturedStatement::sql)
                            .contains("SELECT 1");
                });
    }

    @Test
    void scansThePoolWrappedInALazyConnectionDataSourceProxy() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataSource.class)).isInstanceOf(LazyConnectionDataSourceProxy.class);

            var report = context.getBean(DatabaseAdvisorController.class).scan();

            assertThat(report.scan().status()).isNotEqualTo("DISABLED");
            assertThat(report.dataSourceNames()).containsExactly("dataSource");
            assertThat(report.dataSources()).singleElement().satisfies(dataSource -> {
                assertThat(dataSource.name()).isEqualTo("dataSource");
                assertThat(dataSource.status()).isEqualTo("AVAILABLE");
            });
        });
    }

    @Test
    void tracesThePoolWrappedInALazyConnectionDataSourceProxy() {
        runner.run(context -> {
            LazyConnectionDataSourceProxy lazy = (LazyConnectionDataSourceProxy) context.getBean(DataSource.class);
            assertThat(lazy.getTargetDataSource()).isInstanceOf(SqlTracedDataSource.class);

            try (Connection connection = lazy.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }

            SqlTraceRecorder recorder = context.getBean(SqlTraceRecorder.class);
            assertThat(recorder.dataSourceNames()).contains("dataSource");
            assertThat(recorder.recent())
                    .extracting(SqlTraceRecorder.CapturedStatement::sql)
                    .contains("SELECT 1");
        });
    }

    @Test
    void keepsResolvingTheHikariPoolBehindTheProxy() {
        runner.run(context -> assertThat(HikariDataSourceDiscovery.discover(context.getBeanFactory()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.beanName()).isEqualTo("dataSource")));
    }
}
