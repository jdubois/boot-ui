package io.github.jdubois.bootui.autoconfigure.mappings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.ActuatorMappingsController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * R2 optional-dependency absence smoke test for the Mappings panel.
 *
 * <p>The Actuator-backed {@link SpringMappingProvider} and the raw {@link ActuatorMappingsController}
 * are the only touch-points for the Actuator mappings types and are wired behind
 * {@code @ConditionalOnClass(MappingsEndpoint)}. This test proves the gate fails closed: with
 * {@code org.springframework.boot.actuate.web.mappings.MappingsEndpoint} removed from the classpath,
 * BootUI still starts, neither the provider nor the raw controller bean is defined, the always-active
 * engine {@link MappingsService} and the neutral {@link MappingsController} are still present (so
 * {@code /flat} serves an empty report), and no {@code NoClassDefFoundError} /
 * {@code ClassNotFoundException} leaks from the Actuator-typed bean factory or the panel availability
 * gate.</p>
 */
class MappingsBackendAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void doesNotWireActuatorBeansButStillServesEmptyFlatWhenMappingsEndpointIsAbsent() {
        runner.withClassLoader(
                        new FilteredClassLoader("org.springframework.boot.actuate.web.mappings.MappingsEndpoint"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringMappingProvider.class);
                    assertThat(context).doesNotHaveBean(ActuatorMappingsController.class);
                    assertThat(context).hasSingleBean(MappingsService.class);
                    assertThat(context).hasSingleBean(MappingsController.class);
                    assertThat(context.getBean(MappingsController.class)
                                    .flatMappings(null, null, null)
                                    .total())
                            .isZero();
                });
    }
}
