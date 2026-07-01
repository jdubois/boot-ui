package io.github.jdubois.bootui.autoconfigure.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.engine.health.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * R2 optional-dependency absence smoke test for the Health panel.
 *
 * <p>The Actuator-backed {@code SpringHealthProvider} is the only touch-point for the Actuator health
 * types and is wired behind {@code @ConditionalOnClass(HealthEndpoint)}. This test proves the gate fails
 * closed: with {@code org.springframework.boot.health.actuate.endpoint.HealthEndpoint} removed from the
 * classpath, BootUI still starts, no {@code SpringHealthProvider} bean is defined, the always-active
 * engine {@link HealthService} and the thin {@link HealthController} are still present (so the panel
 * renders a DISABLED root), and no {@code NoClassDefFoundError} / {@code ClassNotFoundException} leaks
 * from the Actuator-typed bean factory or the panel availability gate.</p>
 */
class HealthAdvisorAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void doesNotWireHealthProviderButStillServesDisabledRootWhenActuatorHealthIsAbsent() {
        runner.withClassLoader(
                        new FilteredClassLoader("org.springframework.boot.health.actuate.endpoint.HealthEndpoint"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringHealthProvider.class);
                    assertThat(context).hasSingleBean(HealthService.class);
                    assertThat(context).hasSingleBean(HealthController.class);
                    assertThat(context.getBean(HealthController.class).health().status())
                            .isEqualTo("DISABLED");
                    assertThat(context.getBean(HealthController.class).health().available())
                            .isFalse();
                });
    }
}
