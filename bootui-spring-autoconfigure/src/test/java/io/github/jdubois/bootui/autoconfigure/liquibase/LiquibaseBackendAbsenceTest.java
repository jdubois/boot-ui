package io.github.jdubois.bootui.autoconfigure.liquibase;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.LiquibaseController;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * R2 optional-dependency absence smoke test.
 *
 * <p>The Liquibase panel pulls the optional {@code liquibase.*} API and gates its bean wiring (the engine
 * {@link LiquibaseService} and the {@link LiquibaseController}) behind {@code @ConditionalOnClass}. This test
 * proves the gate fails closed: with {@code liquibase.integration.spring.SpringLiquibase} removed from the
 * classpath, BootUI still starts, neither the engine {@link LiquibaseService} bean nor the
 * {@link LiquibaseController} bean is defined, and no {@code NoClassDefFoundError} /
 * {@code ClassNotFoundException} leaks from the Liquibase-typed provider or controller.</p>
 */
class LiquibaseBackendAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void doesNotWireLiquibasePanelWhenLiquibaseIsAbsent() {
        runner.withClassLoader(new FilteredClassLoader("liquibase.integration.spring.SpringLiquibase"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LiquibaseService.class);
                    assertThat(context).doesNotHaveBean(LiquibaseController.class);
                });
    }
}
