package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.MemoryPoolDto;
import io.github.bootui.core.BootUiDtos.MemoryReport;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/memory")
public class MemoryController {

    @GetMapping
    public MemoryReport memory() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        MemoryPoolDto heap = toDto("Heap", memBean.getHeapMemoryUsage());
        MemoryPoolDto nonHeap = toDto("Non-Heap", memBean.getNonHeapMemoryUsage());

        List<MemoryPoolDto> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                pools.add(toDto(pool.getName(), usage));
            }
        }

        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        String suggested = buildSuggestedOptions(memBean.getHeapMemoryUsage());

        return new MemoryReport(heap, nonHeap, pools, inputArgs, suggested);
    }

    private MemoryPoolDto toDto(String name, MemoryUsage usage) {
        long used = usage.getUsed();
        long committed = usage.getCommitted();
        long max = usage.getMax();
        int pct = (max > 0) ? (int) (used * 100L / max) : (committed > 0 ? (int) (used * 100L / committed) : 0);
        return new MemoryPoolDto(name, used, committed, max, pct);
    }

    /**
     * Calculates recommended JVM startup options based on current heap usage.
     *
     * <p>The strategy:
     * <ul>
     *   <li>-Xms: next multiple of 128 MB above current heap committed (minimum 64 MB)</li>
     *   <li>-Xmx: next multiple of 256 MB above current heap max (or committed when max is -1)</li>
     *   <li>G1GC, container-support, and other best-practice flags</li>
     * </ul>
     */
    private String buildSuggestedOptions(MemoryUsage heapUsage) {
        long committed = heapUsage.getCommitted();
        long max = heapUsage.getMax() > 0 ? heapUsage.getMax() : committed;

        long xmsMb = roundUpTo(committed / (1024 * 1024), 128);
        if (xmsMb < 64) xmsMb = 64;

        long xmxMb = roundUpTo(max / (1024 * 1024), 256);
        if (xmxMb < 256) xmxMb = 256;

        // Determine GC recommendation: prefer ZGC for large heaps (>= 4 GB), G1GC otherwise
        String gcFlag = xmxMb >= 4096 ? "-XX:+UseZGC -XX:+ZGenerational" : "-XX:+UseG1GC";

        return "-Xms" + xmsMb + "m" +
               " -Xmx" + xmxMb + "m" +
               " " + gcFlag +
               " -XX:+UseContainerSupport" +
               " -XX:MaxRAMPercentage=75.0" +
               " -XX:InitialRAMPercentage=50.0" +
               " -XX:+ExitOnOutOfMemoryError" +
               " -XX:+HeapDumpOnOutOfMemoryError" +
               " -Djava.security.egd=file:/dev/./urandom";
    }

    private long roundUpTo(long value, long multiple) {
        if (value <= 0) return multiple;
        return ((value + multiple - 1) / multiple) * multiple;
    }
}
