package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin Spring MVC adapter over the framework-neutral engine {@link HealthService}.
 *
 * <p>The Actuator-specific mapping lives in {@code SpringHealthProvider} (gated behind
 * {@code @ConditionalOnClass}); the structural "only default contributors" test and the
 * DISABLED/setup-guidance shaping live in the engine service. This controller only exposes the GET and
 * keeps {@link #health()} as the call site the MCP {@code get_health} tool invokes.</p>
 */
@RestController
@RequestMapping("/bootui/api/health")
public class HealthController {

    private final HealthService service;

    public HealthController(HealthService service) {
        this.service = service;
    }

    @GetMapping
    public HealthNodeDto health() {
        return service.health();
    }
}
