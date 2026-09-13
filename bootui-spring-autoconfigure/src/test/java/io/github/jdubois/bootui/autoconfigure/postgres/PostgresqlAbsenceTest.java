package io.github.jdubois.bootui.autoconfigure.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.engine.postgres.PostgresInsightService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Absence smoke test for the PostgreSQL panel.
 *
 * <p>Like the Database Advisor, this panel's only hard dependency is {@code javax.sql.DataSource}, which is
 * core JDK, so both the engine {@link PostgresInsightService} and the thin {@link PostgresqlController} are
 * wired unconditionally. This test proves the fail-closed behavior: with no {@code DataSource} bean present,
 * BootUI still starts, both beans are present (so the panel renders a DISABLED root rather than throwing),
 * and a user-triggered read reports {@code DISABLED} instead of leaking a class-loading error or opening a
 * connection against a datasource that does not exist.</p>
 */
class PostgresqlAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void servesADisabledRootWhenNoDataSourceBeanIsPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PostgresInsightService.class);
            assertThat(context).hasSingleBean(PostgresqlController.class);

            PostgresqlController controller = context.getBean(PostgresqlController.class);
            assertThat(controller.postgresql().status()).isEqualTo("NOT_READ");
            assertThat(controller.read().status()).isEqualTo("DISABLED");
        });
    }
}
