package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;

/**
 * Resolves the route of a captured exchange once, the same way for the exchange list, the route summary and
 * the Live Activity slowest-request KPI, so a route-summary row always links to exactly the exchanges it
 * counts.
 */
final class HttpRoutes {

    private HttpRoutes() {}

    /** The route {@code exchange} is grouped under: framework template, declared mapping, or masked path. */
    static RouteLabel labelOf(CapturedHttpExchange exchange, RouteTemplateResolver templates) {
        String path = exchange.uri() == null ? null : exchange.uri().getPath();
        return RouteLabel.of(exchange.method(), path, exchange.routeTemplate(), exchange.operation(), templates);
    }
}
