package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The application's declared routes that no request of this run reached ({@code docs/PLAN-v2.md} §5.5), so an empty
 * observation list over a route is never mistaken for a healthy route. Framework endpoints, such as Spring Boot's error
 * controller and Actuator, and catch-all patterns are left out: they are not the application's own routes.
 */
final class NotExercisedRoutes {

    /** Methods that mean any method, as a mapping without a method condition is listed. */
    private static final Set<String> ANY_METHOD = Set.of("", "ANY", "*");

    private NotExercisedRoutes() {}

    /**
     * The declared routes {@code exercised} does not contain, as {@code METHOD route} labels, sorted.
     *
     * @param exercised the route labels of the requests this run completed, such as {@code GET /api/orders/{id}}
     */
    static List<String> of(List<MappingDto> declared, Collection<String> exercised) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        Set<String> labels = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (String label : exercised) {
            String base = withoutOperation(label);
            labels.add(base);
            int space = base.indexOf(' ');
            routes.add(space < 0 ? base : base.substring(space + 1));
        }
        TreeSet<String> missing = new TreeSet<>();
        for (MappingDto mapping : declared) {
            if (mapping == null || !applicationRoute(mapping)) {
                continue;
            }
            String route = RouteTemplateResolver.canonical(mapping.pattern());
            String method =
                    mapping.method() == null ? "" : mapping.method().trim().toUpperCase(Locale.ROOT);
            if (ANY_METHOD.contains(method)) {
                if (!routes.contains(route)) {
                    missing.add(RouteLabel.idOf("ANY", route));
                }
            } else if (!labels.contains(RouteLabel.idOf(method, route))) {
                missing.add(RouteLabel.idOf(method, route));
            }
        }
        return new ArrayList<>(missing);
    }

    private static boolean applicationRoute(MappingDto mapping) {
        String pattern = mapping.pattern();
        if (pattern == null || !pattern.startsWith("/") || pattern.contains("*")) {
            return false;
        }
        String handler = mapping.handler() == null ? "" : mapping.handler();
        return !handler.contains("org.springframework.boot.")
                && !handler.startsWith("Actuator ")
                && !quarkusEndpoint(handler, pattern)
                && !"/error".equals(pattern);
    }

    /**
     * Whether a route is one of Quarkus's own: a class of an extension's runtime module ({@code io.quarkus.*.runtime.*},
     * where every extension keeps its runtime classes) or an {@code io.quarkus} handler under the non-application root
     * {@code /q/}. An application whose own packages start with {@code io.quarkus}, such as the Quarkus samples'
     * {@code io.quarkus.sample.superheroes}, keeps its routes.
     */
    static boolean quarkusEndpoint(String handler, String pattern) {
        if (!handler.startsWith("io.quarkus.")) {
            return false;
        }
        return handler.contains(".runtime.") || pattern.equals("/q") || pattern.startsWith("/q/");
    }

    /** A GraphQL operation subdivides its route, so {@code POST /graphql (query Products)} exercised {@code POST /graphql}. */
    private static String withoutOperation(String label) {
        int operation = label.indexOf(" (");
        return operation < 0 ? label : label.substring(0, operation);
    }
}
