package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.web.BootUiStaticResourceConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Reactive (WebFlux) sibling of {@link BootUiStaticResourceConfigurer}: serves the compiled Vue SPA
 * assets from a dedicated static resource handler so the dashboard keeps loading even when a host app
 * disables Spring Boot's default static resource handling. See that class's Javadoc for the full
 * rationale; {@code spring.web.resources.add-mappings} is the same shared Spring Boot property honored
 * by both the {@code WebMvcAutoConfiguration} and {@code WebFluxAutoConfiguration} resource handlers.
 */
public class ReactiveBootUiStaticResourceConfigurer implements WebFluxConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ReactiveBootUiStaticResourceConfigurer.class);

    private final Environment environment;

    public ReactiveBootUiStaticResourceConfigurer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(BootUiStaticResourceConfigurer.ASSET_PATH_PATTERN)
                .addResourceLocations(BootUiStaticResourceConfigurer.ASSET_LOCATION);

        if (!environment.getProperty("spring.web.resources.add-mappings", Boolean.class, true)) {
            log.warn(
                    "spring.web.resources.add-mappings is false, which disables Spring Boot's default static "
                            + "resource handling. BootUI registered its own handler for '{}' (serving {}) so the "
                            + "dashboard UI still loads.",
                    BootUiStaticResourceConfigurer.ASSET_PATH_PATTERN,
                    BootUiStaticResourceConfigurer.ASSET_LOCATION);
        }
    }
}
