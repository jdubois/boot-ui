package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.CamelRouteDto;
import io.github.jdubois.bootui.core.BootUiDtos.CamelRoutesReport;
import io.github.jdubois.bootui.core.BootUiDtos.CamelStatsDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedRouteMBean;
import org.apache.camel.console.DevConsole;
import org.apache.camel.console.DevConsoleRegistry;
import org.apache.camel.spi.RouteController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bootui/api/camel")
public class CamelController {

    private static final Logger log = LoggerFactory.getLogger(CamelController.class);

    private final CamelContext camelContext;
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bootui-camel-lifecycle");
        t.setDaemon(true);
        return t;
    });

    public CamelController(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @GetMapping("/routes")
    public CamelRoutesReport routes() {
        List<Route> routes = camelContext.getRoutes();
        RouteController rc = camelContext.getRouteController();
        List<CamelRouteDto> dtos = new ArrayList<>();
        for (Route route : routes) {
            ServiceStatus status = rc.getRouteStatus(route.getRouteId());
            Endpoint ep = route.getEndpoint();
            dtos.add(new CamelRouteDto(
                    route.getRouteId(),
                    ep != null ? ep.getEndpointUri() : null,
                    status != null ? status.name() : "Unknown",
                    route.getUptime(),
                    route.getUptimeMillis(),
                    route.getDescription()));
        }
        dtos.sort(java.util.Comparator.comparing(CamelRouteDto::routeId, String.CASE_INSENSITIVE_ORDER));
        boolean diagramAvailable = findConsole("route-diagram") != null;
        CamelStatsDto stats = aggregateStats(routes);
        return new CamelRoutesReport(
                camelContext.getVersion(), camelContext.getStatus().name(), dtos.size(), dtos, diagramAvailable, stats);
    }

    @GetMapping("/routes/diagram")
    public Object diagram(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false, defaultValue = "light") String theme) {
        DevConsole console = findConsole("route-diagram");
        if (console == null) {
            return Map.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("theme", theme);
        params.put("metric", "true");
        if (filter != null && !filter.isBlank()) {
            params.put("filter", filter);
        }
        return console.call(DevConsole.MediaType.JSON, params);
    }

    @PostMapping("/routes/{id}/start")
    public Map<String, String> startRoute(@PathVariable String id) {
        return doLifecycle(id, "start");
    }

    @PostMapping("/routes/{id}/stop")
    public Map<String, String> stopRoute(@PathVariable String id) {
        return doLifecycle(id, "stop");
    }

    @PostMapping("/routes/{id}/suspend")
    public Map<String, String> suspendRoute(@PathVariable String id) {
        return doLifecycle(id, "suspend");
    }

    @PostMapping("/routes/{id}/resume")
    public Map<String, String> resumeRoute(@PathVariable String id) {
        return doLifecycle(id, "resume");
    }

    private CamelStatsDto aggregateStats(List<Route> routes) {
        ManagedCamelContext mcc =
                camelContext.getCamelContextExtension().getContextPlugin(ManagedCamelContext.class);
        if (mcc == null) {
            return null;
        }
        long totalExchanges = 0;
        long totalFailed = 0;
        long totalInflight = 0;
        long maxPt = 0;
        long weightedMeanSum = 0;
        List<ManagedRouteMBean> managed = routes.stream()
                .map(r -> mcc.getManagedRoute(r.getRouteId()))
                .filter(Objects::nonNull)
                .toList();
        if (managed.isEmpty()) {
            return null;
        }
        for (ManagedRouteMBean mrb : managed) {
            long exchanges = mrb.getExchangesTotal();
            totalExchanges += exchanges;
            totalFailed += mrb.getExchangesFailed();
            totalInflight += mrb.getExchangesInflight();
            weightedMeanSum += mrb.getMeanProcessingTime() * exchanges;
            maxPt = Math.max(maxPt, mrb.getMaxProcessingTime());
        }
        long meanPt = totalExchanges > 0 ? weightedMeanSum / totalExchanges : 0;
        return new CamelStatsDto(totalExchanges, totalFailed, totalInflight, meanPt, maxPt);
    }

    private Map<String, String> doLifecycle(String routeId, String action) {
        if (camelContext.getRoute(routeId) == null) {
            return Map.of("status", "error", "message", "Route not found: " + routeId);
        }
        try {
            RouteController rc = camelContext.getRouteController();
            switch (action) {
                case "start" -> rc.startRoute(routeId);
                case "stop" -> {
                    lifecycleExecutor.submit(() -> {
                        try {
                            rc.stopRoute(routeId);
                        } catch (Exception e) {
                            log.warn("Failed to stop route {}", routeId, e);
                        }
                    });
                    return Map.of("status", "ok", "message", "Stop requested", "routeStatus", "Stopping");
                }
                case "suspend" -> rc.suspendRoute(routeId);
                case "resume" -> rc.resumeRoute(routeId);
            }
            ServiceStatus status = rc.getRouteStatus(routeId);
            return Map.of("status", "ok", "routeStatus", status != null ? status.name() : "Unknown");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    private DevConsole findConsole(String id) {
        DevConsoleRegistry dcr =
                camelContext.getCamelContextExtension().getContextPlugin(DevConsoleRegistry.class);
        if (dcr == null || !dcr.isEnabled()) {
            return null;
        }
        return dcr.resolveById(id);
    }
}
