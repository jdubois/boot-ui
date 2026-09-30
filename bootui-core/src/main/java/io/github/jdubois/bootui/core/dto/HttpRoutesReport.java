package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Route performance rankings for the HTTP Exchanges panel.
 *
 * <p>Served from {@code GET /bootui/api/http-exchanges/routes} and the {@code get_http_routes} MCP tool,
 * beside — not instead of — the chronological {@link HttpExchangesReport}. Everything is derived from the
 * exchanges BootUI already retains: no request filter, meter or second recorder is added, and nothing is
 * retained beyond the window described by {@link #window()}.</p>
 *
 * @param available whether HTTP exchanges are recorded and a summary could be computed
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param window the bounded retained window every figure is computed over
 * @param routes the union of each ranking criterion's top routes, most requested first
 * @param topPerCriterion how many routes each criterion contributes; re-sorting {@link #routes()} by any
 *     criterion and taking this many rows yields that criterion's exact top list
 * @param routesTruncated whether further routes exist beyond {@link #routes()}
 * @param distinctRoutes distinct routes observed in the window
 * @param notes plain-language explanations of scope, route resolution and limitations
 */
public record HttpRoutesReport(
        boolean available,
        String unavailableReason,
        HttpRouteWindowDto window,
        List<HttpRouteDto> routes,
        int topPerCriterion,
        boolean routesTruncated,
        int distinctRoutes,
        List<String> notes) {

    public HttpRoutesReport {
        window = window == null ? HttpRouteWindowDto.empty() : window;
        routes = DtoCollections.immutableCopy(routes);
        notes = DtoCollections.immutableCopy(notes);
    }

    public static HttpRoutesReport unavailable(String reason) {
        return new HttpRoutesReport(false, reason, HttpRouteWindowDto.empty(), List.of(), 0, false, 0, List.of());
    }
}
