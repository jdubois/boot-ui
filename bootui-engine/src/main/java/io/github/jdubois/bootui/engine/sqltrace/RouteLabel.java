package io.github.jdubois.bootui.engine.sqltrace;

import java.util.Locale;

/**
 * The grouping identity of an inbound request route: its method plus a low-cardinality, value-free route.
 *
 * <p>SQL Trace route attribution and the HTTP Exchanges route summary both group requests by route, and they
 * must agree exactly on what a route is, so both resolve it here. Three sources are tried in order:</p>
 *
 * <ol>
 *   <li>{@link Source#FRAMEWORK_TEMPLATE} — the handler pattern the runtime itself matched, such as
 *       {@code /api/orders/{id}}. Spring MVC and Spring WebFlux publish it at request time. It is rendered
 *       exactly as a declared template is (see {@link RouteTemplateResolver#canonical}), so the same route
 *       gets the same label from either tier and on every adapter.</li>
 *   <li>{@link Source#DECLARED_MAPPING} — the single best declared route matching the path, from the
 *       application's own mappings (see {@link RouteTemplateResolver}). Quarkus relies on this tier, and the
 *       Spring stacks fall back to it when no framework template was recorded. Ambiguous declarations resolve
 *       to no template.</li>
 *   <li>{@link Source#MASKED_PATH} — the concrete path with every segment that reads like a value replaced
 *       by {@code {value}} (see {@link RoutePathMasker}). Every position that any matching declaration says
 *       is a parameter is masked too, so ambiguous declarations never let a word-shaped value through, and a
 *       brace-delimited segment on a real request is treated as a value, not as template syntax. A path that
 *       no declaration matches at all keeps segments that read like route words: without a declaration, a
 *       word-shaped parameter cannot be told apart from a fixed route word.</li>
 * </ol>
 *
 * <p>A query string never reaches a route: the framework template and declared mappings carry none, and the
 * resolver and masker both strip one.</p>
 *
 * @param id the grouping key, {@code METHOD route}
 * @param method the upper-cased HTTP method, or {@code UNKNOWN}
 * @param route the resolved template or masked path
 * @param source which tier produced {@link #route()}
 */
public record RouteLabel(String id, String method, String route, Source source) {

    /** Where a route label came from, strongest first. */
    public enum Source {
        FRAMEWORK_TEMPLATE,
        DECLARED_MAPPING,
        MASKED_PATH;

        /** Whether this source is a real template rather than a masked observation of a path. */
        public boolean isTemplate() {
            return this != MASKED_PATH;
        }
    }

    /** The method BootUI groups under when a request carried none. */
    public static final String UNKNOWN_METHOD = "UNKNOWN";

    /**
     * Resolves the route for one request.
     *
     * @param method the request method; blank becomes {@link #UNKNOWN_METHOD}
     * @param path the decoded request path, without query string; may be {@code null}
     * @param frameworkTemplate the pattern the runtime matched, or {@code null} when none was recorded
     * @param declared the application's declared routes; {@code null} means none are known
     */
    /**
     * The route of a request that carried a named operation, such as a GraphQL {@code query ProductList} posted to
     * {@code /graphql}: the operation is part of the route, so each operation is ranked and filtered on its own
     * ({@code docs/PLAN-v2.md} §5.1).
     */
    public static RouteLabel of(
            String method, String path, String frameworkTemplate, String operation, RouteTemplateResolver declared) {
        RouteLabel base = of(method, path, frameworkTemplate, declared);
        if (operation == null || operation.isBlank()) {
            return base;
        }
        String route = base.route() + " (" + operation.trim() + ")";
        return new RouteLabel(idOf(base.method(), route), base.method(), route, base.source());
    }

    public static RouteLabel of(String method, String path, String frameworkTemplate, RouteTemplateResolver declared) {
        String normalizedMethod = normalizeMethod(method);
        String reported = RouteTemplateResolver.canonical(frameworkTemplate);
        if (reported != null) {
            return new RouteLabel(
                    idOf(normalizedMethod, reported), normalizedMethod, reported, Source.FRAMEWORK_TEMPLATE);
        }
        String template = declared == null ? null : declared.resolve(path);
        if (template != null) {
            return new RouteLabel(
                    idOf(normalizedMethod, template), normalizedMethod, template, Source.DECLARED_MAPPING);
        }
        String masked = RoutePathMasker.maskObserved(
                path, declared == null ? java.util.Set.of() : declared.parameterPositions(path));
        return new RouteLabel(idOf(normalizedMethod, masked), normalizedMethod, masked, Source.MASKED_PATH);
    }

    /** The grouping key for {@code method} and an already-resolved {@code route}. */
    public static String idOf(String method, String route) {
        return normalizeMethod(method) + " " + route;
    }

    private static String normalizeMethod(String method) {
        return method == null || method.isBlank()
                ? UNKNOWN_METHOD
                : method.trim().toUpperCase(Locale.ROOT);
    }
}
