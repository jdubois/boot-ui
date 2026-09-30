package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.function.Supplier;

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
        return RouteTemplateResolver.lazy(() -> mappings(mappings));
    }

    /**
     * Resolvers that share one index of the declared routes, read once, the first time a path needs it. The
     * build-time mappings never change at runtime, so a panel polled every few seconds indexes them once.
     */
    static Supplier<RouteTemplateResolver> caching(Instance<? extends MappingProvider> mappings) {
        if (mappings == null) {
            return RouteTemplateResolver::empty;
        }
        return RouteTemplateResolver.caching(() -> mappings(mappings));
    }

    private static List<MappingDto> mappings(Instance<? extends MappingProvider> mappings) {
        if (!mappings.isResolvable()) {
            return List.of();
        }
        MappingProvider provider = mappings.get();
        return provider.available() ? provider.mappings() : List.of();
    }
}
