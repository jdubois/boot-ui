package io.github.jdubois.bootui.autoconfigure.kernel;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.KernelInsightsReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the Kernel Insights panel: a read-only status plus a user-initiated capture that runs the
 * local Inspektor Gadget {@code ig} binary for a few seconds and returns normalized kernel events.
 *
 * <p>The capture is a state-changing action (it launches an external process), so the panel is
 * registered as action-capable and honors the {@code bootui.panels.kernel-insights.read-only} toggle
 * through {@code PanelAccessFilter}. Captures are never run on load.
 */
@RestController
@RequestMapping("/bootui/api/kernel-insights")
public class KernelInsightsController {

    private final KernelInsightsService service;

    @Autowired
    public KernelInsightsController(BootUiProperties properties) {
        this(new KernelInsightsService(properties));
    }

    KernelInsightsController(KernelInsightsService service) {
        this.service = service;
    }

    @GetMapping
    public KernelInsightsReport status() {
        return service.status();
    }

    @PostMapping("/scan")
    public KernelInsightsReport scan() {
        return service.scan();
    }
}
