package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Absence smoke test for the Database Advisor panel.
 *
 * <p>Unlike the Hibernate advisor, the Database Advisor's only hard dependency is
 * {@code javax.sql.DataSource}, which is core JDK — so it is always wired unconditionally, with no
 * {@code @ConditionalOnClass} gate. This test proves the fail-closed behavior instead: with no
 * {@code DataSource} bean present at all, BootUI still starts, the always-active engine
 * {@link DatabaseAdvisorScanner} and the thin {@link DatabaseAdvisorController} are still present (so the
 * panel renders a DISABLED root rather than throwing), and scanning never leaks a
 * {@code NoClassDefFoundError}/{@code ClassNotFoundException} or attempts to open a connection against a
 * datasource that does not exist.</p>
 */
class DatabaseAdvisorAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void servesADisabledRootWhenNoDataSourceBeanIsPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DatabaseAdvisorScanner.class);
            assertThat(context).hasSingleBean(DatabaseAdvisorController.class);

            var report = context.getBean(DatabaseAdvisorController.class).scan();
            assertThat(report.scan().status()).isEqualTo("DISABLED");
            assertThat(report.dataSourceNames()).isEmpty();
            assertThat(report.results()).isEmpty();
        });
    }
}
