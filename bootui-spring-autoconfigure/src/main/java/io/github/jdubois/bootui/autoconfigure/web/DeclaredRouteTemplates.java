package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The application's declared route templates, read from the Mappings panel's existing provider, so a
 * request whose handler pattern was not recorded is still grouped by a declared route instead of a masked
 * path. Shared by SQL Trace route attribution, the HTTP Exchanges route summary and the Live Activity KPIs,
 * so all three label a request the same way. No new route enumeration is introduced, and an absent,
 * unavailable or failing provider simply means no templates.
 *
 * <p>The mappings are read lazily, the first time a path needs resolving: most Spring requests carry the
 * framework's own template, so a Live Activity refresh usually never enumerates the application's routes.</p>
 */
public final class DeclaredRouteTemplates {

    private DeclaredRouteTemplates() {}

    public static RouteTemplateResolver from(ObjectProvider<MappingProvider> mappingProvider) {
        if (mappingProvider == null) {
            return RouteTemplateResolver.empty();
        }
        return RouteTemplateResolver.lazy(() -> mappings(mappingProvider));
    }

    /**
     * Resolvers that share one index of the declared routes, read once, the first time a path needs it. For a
     * panel polled every few seconds, such as HTTP Exchanges and Live Activity.
     */
    public static Supplier<RouteTemplateResolver> caching(ObjectProvider<MappingProvider> mappingProvider) {
        if (mappingProvider == null) {
            return RouteTemplateResolver::empty;
        }
        return RouteTemplateResolver.caching(() -> mappings(mappingProvider));
    }

    /** The declared routes themselves, read on each call, or none without a mappings provider. */
    public static Supplier<List<MappingDto>> declared(ObjectProvider<MappingProvider> mappingProvider) {
        return mappingProvider == null ? List::of : () -> mappings(mappingProvider);
    }

    private static List<MappingDto> mappings(ObjectProvider<MappingProvider> mappingProvider) {
        MappingProvider provider = mappingProvider.getIfAvailable();
        return provider == null || !provider.available() ? List.of() : provider.mappings();
    }
}
