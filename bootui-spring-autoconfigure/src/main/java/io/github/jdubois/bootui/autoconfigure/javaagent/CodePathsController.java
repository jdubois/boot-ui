package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Code Paths panel ({@code docs/PLAN-v2.md} §5.14), shared by the Spring MVC and WebFlux adapters: which application
 * bean methods each route spends its time in, from the BootUI agent's {@code code-paths} sensor. Reads only the route and
 * request trees this run built; it starts no scan, network call, or mutation. Without the agent every read answers
 * unavailable with the Java Agent panel's reason.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/code-paths")
public class CodePathsController {

    private final CodePathsService service;

    public CodePathsController(CodePathsService service) {
        this.service = service;
    }

    @GetMapping
    public CodePathsReport report() {
        return service.report();
    }

    @GetMapping("/route")
    public CodePathsRouteTreeReport route(
            @RequestParam(name = "route", required = false) String route,
            @RequestParam(name = "depth", required = false) Integer depth,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.routeTree(route, depth, offset, limit);
    }

    @GetMapping("/requests/{requestId}")
    public CodePathsRequestTreeReport request(@PathVariable String requestId) {
        return service.requestTree(requestId);
    }

    /** Code Paths for agents: {@code get_code_paths} and {@code bootui code paths}. */
    public CodePathsAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }

    /** Why Code Paths is unavailable, or {@code null}; the panel's availability. */
    public String unavailableReason() {
        return service.unavailableReason();
    }
}
