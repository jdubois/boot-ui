package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral Mappings controller. It serves {@code GET /bootui/api/mappings/flat} — the view the
 * BootUI UI consumes — by delegating to the engine {@link MappingsService}, which sorts, queries and
 * pages the flattened, self-filtered mappings supplied by the (optional) {@code MappingProvider}.
 *
 * <p>Actuator types are confined to the gated {@code SpringMappingProvider} and the
 * {@link ActuatorMappingsController} (the raw {@code /mappings} descriptor passthrough), so this
 * controller carries no optional dependency and is always registered: when no mappings backend is
 * available the service returns an empty report.</p>
 */
@RestController
@RequestMapping("/bootui/api/mappings")
public class MappingsController {

    private final MappingsService mappingsService;

    public MappingsController(MappingsService mappingsService) {
        this.mappingsService = mappingsService;
    }

    @GetMapping("/flat")
    public MappingsReport flatMappings(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return mappingsService.report(query, offset, limit);
    }
}
