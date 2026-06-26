package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/live-memory")
public class LiveMemoryController {

    private final MemoryReportProvider provider;

    public LiveMemoryController(MemoryReportProvider provider) {
        this.provider = provider;
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
}
