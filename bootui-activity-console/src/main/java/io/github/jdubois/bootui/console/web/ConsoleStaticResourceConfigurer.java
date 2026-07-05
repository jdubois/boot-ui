package io.github.jdubois.bootui.console.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Serves the compiled BootUI Vue SPA assets (shipped inside {@code bootui-ui} at {@code
 * META-INF/resources/bootui/}) from a dedicated static resource handler, exactly like every other
 * BootUI adapter's static resource configurer &mdash; see the package-level Javadoc for why this class
 * is a small, self-contained duplicate rather than a dependency on {@code bootui-spring-autoconfigure}.
 */
@Configuration
public class ConsoleStaticResourceConfigurer implements WebFluxConfigurer {

    public static final String ASSET_PATH_PATTERN = "/bootui/**";

    public static final String ASSET_LOCATION = "classpath:/META-INF/resources/bootui/";

    private static final Logger log = LoggerFactory.getLogger(ConsoleStaticResourceConfigurer.class);

    private final Environment environment;

    public ConsoleStaticResourceConfigurer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(ASSET_PATH_PATTERN).addResourceLocations(ASSET_LOCATION);

        if (!environment.getProperty("spring.web.resources.add-mappings", Boolean.class, true)) {
            log.warn(
                    "spring.web.resources.add-mappings is false, which disables Spring Boot's default static "
                            + "resource handling. BootUI Activity Console registered its own handler for '{}' "
                            + "(serving {}) so the dashboard UI still loads.",
                    ASSET_PATH_PATTERN,
                    ASSET_LOCATION);
        }
    }
}
