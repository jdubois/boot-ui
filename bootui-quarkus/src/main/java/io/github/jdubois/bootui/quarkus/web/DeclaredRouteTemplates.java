package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import java.util.List;

/**
 * The application's declared JAX-RS routes, captured at build time for the Mappings panel. RESTEasy Reactive
 * registers a single catch-all Vert.x route rather than one route per resource method, so Quarkus has no
 * framework template at capture time; SQL Trace route attribution, the HTTP Exchanges route summary and the
 * Live Activity slowest-request KPI all match the captured path against these declarations instead, the same
 * way. An absent, unavailable or failing provider — production, or a build without the mappings build step —
 * simply means no templates.
 */
final class DeclaredRouteTemplates {

    private DeclaredRouteTemplates() {}

    static RouteTemplateResolver from(Instance<? extends MappingProvider> mappings) {
        if (mappings == null) {
            return RouteTemplateResolver.empty();
        }
        return RouteTemplateResolver.lazy(() -> {
            if (!mappings.isResolvable()) {
                return List.of();
            }
            MappingProvider provider = mappings.get();
            return provider.available() ? provider.mappings() : List.of();
        });
    }
}
