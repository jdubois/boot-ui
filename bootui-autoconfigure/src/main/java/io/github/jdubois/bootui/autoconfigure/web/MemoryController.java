package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryCalculationDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryPoolDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryReport;
import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/bootui/api/memory", "/bootui/api/tuning-advisor"})
public class MemoryController {

    private final MemoryCalculator calculator;
    private final ContainerMemoryLimitDetector containerMemoryLimitDetector;

    public MemoryController() {
        this(new MemoryCalculator(), ContainerMemoryLimitDetector.standard());
    }

    MemoryController(MemoryCalculator calculator) {
        this(calculator, ContainerMemoryLimitDetector.standard());
    }

    MemoryController(MemoryCalculator calculator, ContainerMemoryLimitDetector containerMemoryLimitDetector) {
        this.calculator = calculator;
        this.containerMemoryLimitDetector = containerMemoryLimitDetector;
    }

    @GetMapping
    public MemoryReport memory(
            @RequestParam(name = "totalMemoryMb", required = false) Long totalMemoryMb,
            @RequestParam(name = "threadCount", required = false) Integer threadCount,
            @RequestParam(name = "headRoomPercent", required = false) Integer headRoomPercent) {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();

        MemoryPoolDto heap = toDto("Heap", heapUsage);
        MemoryPoolDto nonHeap = toDto("Non-Heap", nonHeapUsage);

        List<MemoryPoolDto> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                pools.add(toDto(pool.getName(), usage));
            }
        }

        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        int liveThreads = threadBean.getThreadCount();
        int liveClasses = classBean.getLoadedClassCount();
        OptionalLong detectedContainerMemoryLimit = containerMemoryLimitDetector.detectLimit();

        long resolvedTotalBytes = totalMemoryMb != null
                ? totalMemoryMb * 1024L * 1024L
                : detectedContainerMemoryLimit.orElseGet(() -> calculator.defaultTotalMemoryBytes(
                        heapUsage.getCommitted(),
                        nonHeapUsage.getCommitted(),
                        MemoryCalculator.defaultThreadCount(liveThreads),
                        liveClasses));
        int resolvedThreads = threadCount != null ? threadCount : MemoryCalculator.defaultThreadCount(liveThreads);
        int resolvedHeadRoom = headRoomPercent != null ? headRoomPercent : 0;

        MemoryCalculationDto calculation = calculator.calculate(
                resolvedTotalBytes, resolvedThreads, liveClasses, resolvedHeadRoom, liveThreads, liveClasses);
        Long detectedContainerMemoryLimitBytes =
                detectedContainerMemoryLimit.isPresent() ? detectedContainerMemoryLimit.getAsLong() : null;
        KubernetesMemoryRecommendationDto kubernetes = MemoryKubernetesSizer.recommend(
                calculation,
                heapUsage.getCommitted(),
                nonHeapUsage.getCommitted(),
                directBufferMemoryUsedBytes(),
                nativeMemoryTrackingEnabled(inputArgs),
                detectedContainerMemoryLimitBytes);

        return new MemoryReport(heap, nonHeap, pools, inputArgs, calculation.jvmOptions(), calculation, kubernetes);
    }

    private MemoryPoolDto toDto(String name, MemoryUsage usage) {
        long used = usage.getUsed();
        long committed = usage.getCommitted();
        long max = usage.getMax();
        int pct = (max > 0) ? (int) (used * 100L / max) : (committed > 0 ? (int) (used * 100L / committed) : 0);
        return new MemoryPoolDto(name, used, committed, max, pct);
    }

    private long directBufferMemoryUsedBytes() {
        long usedBytes = 0;
        for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equalsIgnoreCase(bufferPool.getName())) {
                usedBytes += Math.max(0, bufferPool.getMemoryUsed());
            }
        }
        return usedBytes;
    }

    private boolean nativeMemoryTrackingEnabled(List<String> inputArgs) {
        for (String inputArg : inputArgs) {
            if (inputArg.startsWith("-XX:NativeMemoryTracking=") && !inputArg.endsWith("=off")) {
                return true;
            }
        }
        return false;
    }
}
