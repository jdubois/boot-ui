package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public LiveMemoryController(MemoryReportProvider provider, MemoryOffloadService offloadService) {
        this.provider = provider;
        this.offloadService = offloadService;
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
}
