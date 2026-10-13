package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.LowFootprintRequest;
import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Live Memory panel, shared by Spring MVC and Spring WebFlux. Its {@code POST /offload} action, <b>Free BootUI
 * memory</b>, is also offered by the JVM Tuning, Heap Dump, and Memory panels; it is gated by the Live Memory panel's
 * enable and read-only toggles like every panel action.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/live-memory")
public class LiveMemoryController {

    private final MemoryReportProvider provider;
    private final MemoryOffloadService offloadService;
    private final LowFootprintService lowFootprintService;

    public LiveMemoryController(
            MemoryReportProvider provider,
            MemoryOffloadService offloadService,
            LowFootprintService lowFootprintService) {
        this.provider = provider;
        this.offloadService = offloadService;
        this.lowFootprintService = lowFootprintService;
    }

    @GetMapping
    public LiveMemoryReport memory(
            @RequestParam(name = "totalMemoryMb", required = false) Long totalMemoryMb,
            @RequestParam(name = "threadCount", required = false) Integer threadCount,
            @RequestParam(name = "headRoomPercent", required = false) Integer headRoomPercent,
            @RequestParam(name = "kubernetesBurstableEnabled", required = false) Boolean kubernetesBurstableEnabled,
            @RequestParam(name = "kubernetesActuatorEnabled", required = false) Boolean kubernetesActuatorEnabled) {
        return provider.buildReport(
                totalMemoryMb, threadCount, headRoomPercent, kubernetesBurstableEnabled, kubernetesActuatorEnabled);
    }

    /** <b>Free BootUI memory</b>: empties BootUI's in-memory stores, then requests a garbage collection. */
    @PostMapping("/offload")
    public MemoryOffloadReport offload() {
        return offloadService.offload();
    }

    /** Whether low-footprint mode is active and which recording components it paused. Does no work. */
    @GetMapping("/footprint")
    public LowFootprintStatus footprint() {
        return lowFootprintService.status();
    }

    /**
     * Turns low-footprint mode on (pause BootUI's recording components, then free memory) or off (resume them). Gated
     * by the Live Memory panel's enable and read-only toggles like every panel action.
     */
    @PostMapping("/footprint")
    public LowFootprintStatus setFootprint(@RequestBody LowFootprintRequest request) {
        return request.enabled() ? lowFootprintService.enable() : lowFootprintService.disable();
    }
}
