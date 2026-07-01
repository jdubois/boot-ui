package io.github.jdubois.bootui.autoconfigure.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.FlywayController;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * R2 optional-dependency absence smoke test for the Flyway panel.
 *
 * <p>The Flyway panel pulls the optional {@code org.flywaydb.core.Flyway} type and gates its bean wiring
 * behind {@code @ConditionalOnClass(Flyway.class)} (both the {@code FlywayBackendConfiguration} producing the
 * engine {@link FlywayService} and the {@link FlywayController}). This test proves the gate fails closed: with
 * {@code org.flywaydb.core.Flyway} removed from the classpath, BootUI still starts, neither the engine
 * {@link FlywayService} bean nor the {@link FlywayController} bean is defined, and no
 * {@code NoClassDefFoundError} / {@code ClassNotFoundException} leaks from the Flyway-typed provider.</p>
 */
class FlywayBackendAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void doesNotWireFlywayPanelWhenFlywayIsAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.flywaydb.core.Flyway"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FlywayService.class);
                    assertThat(context).doesNotHaveBean(FlywayController.class);
                });
    }
}
