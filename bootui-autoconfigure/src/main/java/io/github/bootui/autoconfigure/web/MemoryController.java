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
     * <p>Targets Spring Boot 4 on Java 25.
     *
     * <p>The strategy:
     * <ul>
     *   <li>-Xms: next multiple of 128 MB above current heap committed (minimum 64 MB)</li>
     *   <li>-Xmx: next multiple of 256 MB above current heap max (or committed when max is -1)</li>
     *   <li>G1GC for heaps &lt; 4 GB; ZGC (generational by default on JDK 24+) for larger heaps</li>
     *   <li>{@code -XX:+UseStringDeduplication}: Spring apps allocate many duplicate strings
     *       (bean names, config keys, JSON property names, log templates); deduplication
     *       typically saves 5–15% of heap on G1 and ZGC (since JDK 18)</li>
     *   <li>{@code -XX:+UseCompactObjectHeaders}: JEP 519 graduated this to a product flag
     *       in JDK 25 — shrinks every object header from 12 to 8 bytes on 64-bit JVMs with
     *       compressed oops, saving roughly 10–20% of heap on Spring's small-object-heavy
     *       allocation pattern</li>
     *   <li>OOM safety flags with a writable HeapDumpPath suitable for containers</li>
     * </ul>
     *
     * <p>Notes on flags intentionally <em>not</em> emitted:
     * <ul>
     *   <li>{@code -XX:+UseContainerSupport} is enabled by default since JDK 10.</li>
     *   <li>{@code -XX:+ZGenerational} was removed in JDK 24 (JEP 490); generational
     *       mode is now the default for ZGC and the flag is rejected as unrecognized.</li>
     *   <li>{@code -XX:MaxRAMPercentage} / {@code -XX:InitialRAMPercentage} are only
     *       honored when {@code -Xmx} / {@code -Xms} are <em>not</em> set, so mixing
     *       them with explicit heap sizes is a no-op and misleading.</li>
     *   <li>{@code -Djava.security.egd=file:/dev/./urandom} has been obsolete since
     *       JDK 9 — {@code SecureRandom} already uses {@code NativePRNGNonBlocking}
     *       (which reads {@code /dev/urandom}) by default on Linux.</li>
     *   <li>AOT cache ({@code -XX:AOTCache=...}, JEP 514 stable in JDK 25) is Spring
     *       Boot 4's biggest startup-time win but requires a multi-step training
     *       workflow, so it belongs in documentation, not a copy-paste options string.</li>
     * </ul>
     */
    private String buildSuggestedOptions(MemoryUsage heapUsage) {
        long committed = heapUsage.getCommitted();
        long max = heapUsage.getMax() > 0 ? heapUsage.getMax() : committed;

        long xmsMb = roundUpTo(committed / (1024 * 1024), 128);
        if (xmsMb < 64) xmsMb = 64;

        long xmxMb = roundUpTo(max / (1024 * 1024), 256);
        if (xmxMb < 256) xmxMb = 256;

        String gcFlag = xmxMb >= 4096 ? "-XX:+UseZGC" : "-XX:+UseG1GC";

        return "-Xms" + xmsMb + "m" +
               " -Xmx" + xmxMb + "m" +
               " " + gcFlag +
               " -XX:+UseStringDeduplication" +
               " -XX:+UseCompactObjectHeaders" +
               " -XX:+ExitOnOutOfMemoryError" +
               " -XX:+HeapDumpOnOutOfMemoryError" +
               " -XX:HeapDumpPath=/tmp";
    }

    private long roundUpTo(long value, long multiple) {
        if (value <= 0) return multiple;
        return ((value + multiple - 1) / multiple) * multiple;
    }
}
