package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorSwitchRequest;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Java Agent panel ({@code docs/PLAN-v2.md} §5.13), shared by the Spring MVC and WebFlux adapters: whether the BootUI
 * agent is attached, who holds its claim, and how to attach it. Reading it only reads the bootstrap bridge's status.
 * {@code POST /sensors/{id}} switches a switchable sensor on or off at run time (M5-14), an action the panel's read-only
 * policy refuses like every other.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/java-agent")
public class JavaAgentController {

    private final JavaAgentService service;

    public JavaAgentController(JavaAgentService service) {
        this.service = service;
    }

    @GetMapping
    public JavaAgentReport report() {
        return service.report();
    }

    /** Switches the sensor {@code id} on or off at run time; 400 for another id, 409 when it cannot. */
    @PostMapping("/sensors/{id}")
    public JavaAgentReport switchSensor(
            @PathVariable String id, @RequestBody(required = false) JavaAgentSensorSwitchRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException(
                    "The request body must say {\"enabled\": true} or {\"enabled\": false}.");
        }
        return service.switchSensor(id, request.enabled());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badSwitch(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> refusedSwitch(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, ex);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException ex) {
        return ResponseEntity.status(status)
                .body(Map.of("error", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage()));
    }
}
