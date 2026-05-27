package io.github.jdubois.bootui.autoconfigure.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Registers the {@link BootUiOverridesPropertySource} as the highest-precedence
 * property source so BootUI runtime overrides can win over any other source.
 *
 * <p>Runs very early in the Spring Boot lifecycle through the {@code spring.factories}
 * SPI. Reads any pre-existing overrides from
 * {@code .bootui/application-bootui.properties} (configurable via
 * {@code bootui.overrides-file}).</p>
 */
public class BootUiOverridesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("bootui.overrides-file");
        Path file = Paths.get(configured != null && !configured.isBlank()
            ? configured
            : ".bootui/application-bootui.properties");

        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);
        Map<String, Object> existing = store.load();
        BootUiOverridesPropertySource source = new BootUiOverridesPropertySource(existing);

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(BootUiOverridesPropertySource.NAME)) {
            sources.remove(BootUiOverridesPropertySource.NAME);
        }
        sources.addFirst(source);
    }
}
