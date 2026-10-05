package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.engine.web.HttpProbeLimits;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the HTTP Probe panel for both Spring MVC and Spring WebFlux.
 *
 * <p>Thin transport adapter: the loopback targeting, header filtering, response cap and the inbound
 * {@link HttpProbeLimits} budgets all live in the shared engine {@link HttpProbeService}. Probe input
 * that exceeds a budget is rejected by the engine with {@link IllegalArgumentException}, mapped here to
 * a {@code 400} with the same JSON {@code {"error": ...}} body the other BootUI write endpoints and the
 * Quarkus {@code HttpProbeResource} return. A probe that runs but fails (connection refused, timeout)
 * is not a validation error and still comes back as a {@code 200} with an error payload.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/http-probe")
public class HttpProbeController {

    private final HttpProbeService probeService;

    public HttpProbeController(HttpProbeService probeService) {
        this.probeService = probeService;
    }

    @PostMapping
    public HttpProbeResponse probe(@RequestBody HttpProbeRequest request) {
        return probeService.probe(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
