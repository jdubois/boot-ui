package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Side Effects panel ({@code docs/PLAN-v2.md} §5.16, M5-5a), shared by the Spring MVC and WebFlux adapters: what
 * the application does outside the JVM, per route and call site, from the BootUI agent's side-effect sensors. Reads only
 * the rows this run recorded; it starts no scan, network call, or mutation. Without the agent every read answers
 * unavailable with the Java Agent panel's reason.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/side-effects")
public class SideEffectsController {

    private final SideEffectsService service;

    public SideEffectsController(SideEffectsService service) {
        this.service = service;
    }

    @GetMapping
    public SideEffectsReport report() {
        return service.report();
    }

    @GetMapping("/sensor")
    public SideEffectsSensorReport sensor(
            @RequestParam(name = "sensor", required = false) String sensor,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.sensor(sensor, offset, limit);
    }

    /** Side Effects for agents: {@code get_side_effects} and {@code bootui side-effects}. */
    public SideEffectsAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }

    /** Why Side Effects is unavailable, or {@code null}; the panel's availability. */
    public String unavailableReason() {
        return service.unavailableReason();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
