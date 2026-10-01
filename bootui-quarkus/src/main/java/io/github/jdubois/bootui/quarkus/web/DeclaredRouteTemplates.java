package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The application's declared JAX-RS routes, captured at build time for the Mappings panel. RESTEasy Reactive
 * registers a single catch-all Vert.x route rather than one route per resource method, so Quarkus has no
 * framework template at capture time; SQL Trace route attribution, the HTTP Exchanges route summary and the
 * Live Activity slowest-request KPI all match the captured path against these declarations instead, the same
 * way. An absent, unavailable or failing provider — production, or a build without the mappings build step —
 * simply means no templates.
 *
 * <p>The build-time patterns are relative to the application, while a captured request path carries
 * {@code quarkus.http.root-path} and {@code quarkus.rest.path}. Both prefixes are therefore added to every
 * pattern, so {@code /app/widgets} still matches {@code @Path("/widgets")} under root path {@code /app}. A
 * prefix contributed only by {@code @ApplicationPath} is not known at runtime; such paths fall back to masked
 * paths.</p>
 */
public final class DeclaredRouteTemplates {

    private static final String ROOT_PATH_KEY = "quarkus.http.root-path";

    private static final String REST_PATH_KEY = "quarkus.rest.path";

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
    public static Supplier<RouteTemplateResolver> caching(Instance<? extends MappingProvider> mappings) {
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
        return provider.available() ? mounted(provider.mappings(), mountPrefix()) : List.of();
    }

    /** {@code declared} with {@code prefix} added to every pattern, as requests under that mount arrive. */
    static List<MappingDto> mounted(List<MappingDto> declared, String prefix) {
        if (declared == null || prefix == null || prefix.isEmpty()) {
            return declared == null ? List.of() : declared;
        }
        List<MappingDto> mounted = new ArrayList<>(declared.size());
        for (MappingDto mapping : declared) {
            if (mapping == null || mapping.pattern() == null) {
                continue;
            }
            String pattern = mapping.pattern().startsWith("/") ? mapping.pattern() : "/" + mapping.pattern();
            mounted.add(new MappingDto(
                    mapping.method(),
                    "/".equals(pattern) ? prefix : prefix + pattern,
                    mapping.handler(),
                    mapping.produces(),
                    mapping.consumes()));
        }
        return mounted;
    }

    /** The live {@code quarkus.http.root-path} followed by {@code quarkus.rest.path}, or {@code ""}. */
    static String mountPrefix() {
        try {
            Config config = ConfigProvider.getConfig();
            return normalize(
                            config.getOptionalValue(ROOT_PATH_KEY, String.class).orElse("/"))
                    + normalize(
                            config.getOptionalValue(REST_PATH_KEY, String.class).orElse("/"));
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return "/".equals(trimmed) ? "" : trimmed;
    }
}
