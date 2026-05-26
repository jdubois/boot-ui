package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.MemoryCalculationDto;
import io.github.bootui.core.BootUiDtos.MemoryPoolDto;
import io.github.bootui.core.BootUiDtos.MemoryReport;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/memory")
public class MemoryController {

    private final MemoryCalculator calculator;

    public MemoryController() {
        this(new MemoryCalculator());
    }

    MemoryController(MemoryCalculator calculator) {
        this.calculator = calculator;
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

        long resolvedTotalBytes = totalMemoryMb != null
                ? totalMemoryMb * 1024L * 1024L
                : calculator.defaultTotalMemoryBytes(
                        heapUsage.getCommitted(),
                        nonHeapUsage.getCommitted(),
                        MemoryCalculator.defaultThreadCount(liveThreads),
                        liveClasses);
        int resolvedThreads = threadCount != null
                ? threadCount
                : MemoryCalculator.defaultThreadCount(liveThreads);
        int resolvedHeadRoom = headRoomPercent != null ? headRoomPercent : 0;

        MemoryCalculationDto calculation = calculator.calculate(
                resolvedTotalBytes,
                resolvedThreads,
                liveClasses,
                resolvedHeadRoom,
                liveThreads,
                liveClasses);

        return new MemoryReport(heap, nonHeap, pools, inputArgs, calculation.jvmOptions(), calculation);
    }

    private MemoryPoolDto toDto(String name, MemoryUsage usage) {
        long used = usage.getUsed();
        long committed = usage.getCommitted();
        long max = usage.getMax();
        int pct = (max > 0) ? (int) (used * 100L / max) : (committed > 0 ? (int) (used * 100L / committed) : 0);
        return new MemoryPoolDto(name, used, committed, max, pct);
    }
}

