package io.github.jdubois.bootui.engine.model;

import java.util.List;

/**
 * The application's structure in one run ({@code docs/PLAN-v2.md} §5.4), read once per run from existing providers
 * (mappings, beans and their dependencies, repositories), so an observation never joins one run's events with another
 * run's routes or beans.
 *
 * @param runId the run it was read in
 * @param routes each declared route and the class that handles it
 * @param beans each bean, its type, and the beans it depends on
 * @param beansUnavailable why the beans and their dependencies could not be read, so they are unknown rather than
 *     absent, or {@code null}
 */
public record StructureSnapshot(String runId, List<RouteHandler> routes, List<Bean> beans, String beansUnavailable) {

    public StructureSnapshot {
        routes = routes == null ? List.of() : List.copyOf(routes);
        beans = beans == null ? List.of() : List.copyOf(beans);
    }

    /** A structure whose beans could be read. */
    public StructureSnapshot(String runId, List<RouteHandler> routes, List<Bean> beans) {
        this(runId, routes, beans, null);
    }

    /** No structure, as when no provider is available. */
    public static StructureSnapshot empty(String runId) {
        return new StructureSnapshot(runId, List.of(), List.of());
    }

    /**
     * A declared route.
     *
     * @param route its label, such as {@code GET /api/orders/{id}}
     * @param handlerClass the fully qualified class of its handler, or {@code null}
     */
    public record RouteHandler(String route, String handlerClass) {}

    /**
     * A bean.
     *
     * @param name its name
     * @param type its fully qualified class, or {@code null}
     * @param repository whether it is a repository
     * @param dependencies the names of the beans it depends on
     */
    public record Bean(String name, String type, boolean repository, List<String> dependencies) {

        public Bean {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }
}
