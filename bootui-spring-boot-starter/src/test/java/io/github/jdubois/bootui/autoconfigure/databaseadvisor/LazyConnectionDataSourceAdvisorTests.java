package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.sql.Connection;
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

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class, DataSourceAutoConfiguration.class))
            .withPropertyValues(
                    "bootui.enabled=ON",
                    "bootui.sql-trace.enabled=true",
                    "spring.datasource.url=jdbc:h2:mem:bootui-issue-924;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.connection-fetch=lazy");

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
