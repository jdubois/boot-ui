package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Names the route of a request a Code Inventory record carries without one ({@code docs/PLAN-v2.md} §5.15). The agent
 * captures the route when a method first runs, but a framework may match the route template only after its handler
 * started, so the first call's record can carry the request id alone; the request's own {@code http} event in the
 * runtime journal names its route, as Live Activity and Runtime Insights name it. Reads only the retained events, and
 * stops once every request asked for is named: a request already evicted keeps no route. Code Inventory keeps the
 * routes it found ({@link InventoryRecords#routes}), so it asks only for requests it has not named yet.
 */
public final class JournalRequestRoutes {

    private JournalRequestRoutes() {}

    /**
     * The route labels, such as {@code GET /api/orders/{id}}, of the retained requests among those asked for.
     *
     * @param journal the runtime journal, or {@code null}
     * @param declared the application's declared routes, which name a request's route when the framework recorded no
     *     template, or {@code null}
     */
    public static Function<Set<String>, Map<String, String>> of(
            RuntimeJournal journal, Supplier<RouteTemplateResolver> declared) {
        return requestIds -> {
            Map<String, String> routes = new HashMap<>();
            if (journal == null || requestIds == null || requestIds.isEmpty()) {
                return routes;
            }
            RouteTemplateResolver resolver;
            try {
                resolver = declared == null ? RouteTemplateResolver.empty() : declared.get();
            } catch (RuntimeException ex) {
                resolver = RouteTemplateResolver.empty();
            }
            RouteTemplateResolver routeResolver = resolver == null ? RouteTemplateResolver.empty() : resolver;
            for (JournalEntry entry : journal.entries()) {
                if (routes.size() == requestIds.size()) {
                    break;
                }
                RuntimeEvent event = entry.event();
                if (event.source() == JournalSource.HTTP
                        && event.requestId() != null
                        && requestIds.contains(event.requestId())
                        && event.payload() instanceof HttpPayload http) {
                    routes.put(
                            event.requestId(),
                            RouteLabel.of(
                                            http.method(),
                                            http.path(),
                                            http.routeTemplate(),
                                            http.operation(),
                                            routeResolver)
                                    .id());
                }
            }
            return routes;
        };
    }
}
