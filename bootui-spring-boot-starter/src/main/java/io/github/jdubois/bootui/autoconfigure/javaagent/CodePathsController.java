package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbeRequest;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Code Paths panel ({@code docs/PLAN-v2.md} §5.14), shared by the Spring MVC and WebFlux adapters: which application
 * bean methods each route spends its time in, from the BootUI agent's {@code code-paths} sensor. Reads only the route and
 * request trees this run built; it starts no scan, network call, or mutation. Without the agent every read answers
 * unavailable with the Java Agent panel's reason. Its only actions are method probes (M5-8): starting and stopping one
 * are writes that the panel's read-only policy blocks before they reach this controller.
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

    @GetMapping("/beans")
    public CodePathsBeansReport beans() {
        return service.beans();
    }

    @GetMapping("/requests/{requestId}")
    public CodePathsRequestTreeReport request(@PathVariable String requestId) {
        return service.requestTree(requestId);
    }

    @GetMapping("/probes")
    public CodePathsProbesReport probes() {
        return service.probes().report();
    }

    @PostMapping("/probes")
    public CodePathsProbeDto startProbe(@RequestBody(required = false) CodePathsProbeRequest request) {
        return service.probes()
                .start(
                        request == null ? null : request.method(),
                        request != null && Boolean.TRUE.equals(request.recordShapes()));
    }

    @GetMapping("/probes/{id}")
    public CodePathsProbeDto probe(@PathVariable String id) {
        return service.probes().probe(id);
    }

    @PostMapping("/probes/{id}/stop")
    public CodePathsProbeDto stopProbe(@PathVariable String id) {
        return service.probes().stop(id);
    }

    @DeleteMapping("/probes/{id}")
    public CodePathsProbeDto deleteProbe(@PathVariable String id) {
        return service.probes().stop(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badProbe(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> refusedProbe(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> unknownProbe(NoSuchElementException ex) {
        return error(HttpStatus.NOT_FOUND, ex);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException ex) {
        return ResponseEntity.status(status)
                .body(Map.of("error", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage()));
    }

    /**
     * Method probes for agents: {@code start_method_probe} and {@code bootui probe start}, a refusal answered as an
     * in-band tool error with the REST status.
     */
    public CodePathsProbeDto agentStartProbe(String method) {
        return forAgents(() -> service.probes().startForAgents(method));
    }

    /** Method probes for agents: {@code get_method_probe} and {@code bootui probe show}, never with a shape. */
    public CodePathsProbeDto agentProbe(String id) {
        return forAgents(() -> service.probes().probeForAgents(id));
    }

    private static CodePathsProbeDto forAgents(Supplier<CodePathsProbeDto> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException ex) {
            throw new McpToolClientException(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new McpToolClientException(409, ex.getMessage());
        } catch (NoSuchElementException ex) {
            throw new McpToolClientException(404, ex.getMessage());
        }
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
