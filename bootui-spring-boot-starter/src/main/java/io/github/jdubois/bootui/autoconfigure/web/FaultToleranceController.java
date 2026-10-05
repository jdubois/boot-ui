package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral Fault Tolerance controller. It serves {@code GET /bootui/api/fault-tolerance} by delegating to the
 * engine {@link FaultToleranceService}, which merges the declared policies reported by every available provider
 * with the bounded, metadata-only capture buffer.
 *
 * <p>The panel is read-only by design, so this controller exposes no write mapping at all: BootUI never
 * opens, closes, resets or otherwise mutates a fault tolerance policy. Unlike the scheduling panel it carries no
 * class-level {@code @ConditionalOnClass}, because three different libraries (any of which may be absent)
 * can back it — the service answers {@code faultTolerancePresent: false} with an explicit reason when none is
 * there, which is what the panel renders as an unavailable state.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/fault-tolerance")
public class FaultToleranceController {

    private final FaultToleranceService faultToleranceService;

    public FaultToleranceController(FaultToleranceService faultToleranceService) {
        this.faultToleranceService = faultToleranceService;
    }

    @GetMapping
    public FaultToleranceReport faultTolerance() {
        return faultToleranceService.report();
    }
}
