package io.github.jdubois.bootui.autoconfigure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the BootUI single-page application from a dedicated static resource handler.
 *
 * <p>BootUI's compiled Vue assets ship on the classpath at
 * {@code META-INF/resources/bootui/}. Spring Boot normally serves them through its
 * default static resource handler, but a host application can disable that handler
 * entirely with {@code spring.web.resources.add-mappings=false}, which leaves the
 * dashboard returning {@code 404} even though the {@code /bootui/api/**} endpoints
 * keep working.</p>
 *
 * <p>Because this is a separate {@link WebMvcConfigurer} contribution, the handler it
 * registers is added even when Spring Boot's default {@code /**} handler is
 * suppressed. It is scoped to the fixed {@code /bootui/**} UI path &mdash; matching the
 * SPA's Vite base and {@link BootUiIndexController}'s {@code forward:/bootui/index.html}
 * &mdash; so it never re-exposes the host application's own static resources and keeps
 * the dashboard reachable regardless of the host's static-resource configuration.</p>
 */
public class BootUiStaticResourceConfigurer implements WebMvcConfigurer {

    static final String ASSET_PATH_PATTERN = "/bootui/**";

    static final String ASSET_LOCATION = "classpath:/META-INF/resources/bootui/";

    private static final Logger log = LoggerFactory.getLogger(BootUiStaticResourceConfigurer.class);

    private final Environment environment;

    public BootUiStaticResourceConfigurer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(ASSET_PATH_PATTERN).addResourceLocations(ASSET_LOCATION);

        if (!environment.getProperty("spring.web.resources.add-mappings", Boolean.class, true)) {
            log.warn(
                    "spring.web.resources.add-mappings is false, which disables Spring Boot's default static "
                            + "resource handling. BootUI registered its own handler for '{}' (serving {}) so the "
                            + "dashboard UI still loads.",
                    ASSET_PATH_PATTERN,
                    ASSET_LOCATION);
        }
    }
}
