package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import io.github.jdubois.bootui.core.dto.MemoryPoolDto;
import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.core.env.Environment;

/**
 * Builds the live JVM memory report shared by the Live Memory and JVM Tuning panels.
 */
public class MemoryReportProvider {

    private static final String VIRTUAL_THREADS_PROPERTY = "spring.threads.virtual.enabled";
    private static final String HEALTH_ENDPOINT_ENABLED_PROPERTY = "management.endpoint.health.enabled";
    private static final String HEALTH_PROBES_ENABLED_PROPERTY = "management.endpoint.health.probes.enabled";
    private static final String ENDPOINTS_ENABLED_BY_DEFAULT_PROPERTY = "management.endpoints.enabled-by-default";

    private final MemoryCalculator calculator;
    private final ContainerMemoryLimitDetector containerMemoryLimitDetector;
    private final Environment environment;

    public MemoryReportProvider() {
        this(new MemoryCalculator(), ContainerMemoryLimitDetector.standard(), null);
    }

    public MemoryReportProvider(Environment environment) {
        this(new MemoryCalculator(), ContainerMemoryLimitDetector.standard(), environment);
    }

    MemoryReportProvider(MemoryCalculator calculator) {
        this(calculator, ContainerMemoryLimitDetector.standard(), null);
    }

    MemoryReportProvider(MemoryCalculator calculator, ContainerMemoryLimitDetector containerMemoryLimitDetector) {
        this(calculator, containerMemoryLimitDetector, null);
    }

    MemoryReportProvider(
            MemoryCalculator calculator,
            ContainerMemoryLimitDetector containerMemoryLimitDetector,
            Environment environment) {
        this.calculator = calculator;
        this.containerMemoryLimitDetector = containerMemoryLimitDetector;
        this.environment = environment;
    }

    public LiveMemoryReport buildReport(
            Long totalMemoryMb,
            Integer threadCount,
            Integer headRoomPercent,
            Boolean kubernetesBurstableEnabled,
            Boolean kubernetesActuatorEnabled) {
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
        boolean resolvedVirtualThreadsEnabled = resolveVirtualThreadsEnabled();
        boolean resolvedKubernetesBurstableEnabled = kubernetesBurstableEnabled != null && kubernetesBurstableEnabled;
        boolean resolvedKubernetesActuatorEnabled = resolveKubernetesActuatorEnabled(kubernetesActuatorEnabled);
        int defaultThreadCount = MemoryCalculator.defaultThreadCount(liveThreads, resolvedVirtualThreadsEnabled);

        long resolvedTotalBytes = totalMemoryMb != null
                ? totalMemoryMb * 1024L * 1024L
                : detectedContainerMemoryLimit.orElseGet(() -> calculator.defaultTotalMemoryBytes(
                        heapUsage.getCommitted(),
                        nonHeapUsage.getCommitted(),
                        defaultThreadCount,
                        liveClasses,
                        resolvedVirtualThreadsEnabled));
        int resolvedThreads = threadCount != null ? threadCount : defaultThreadCount;
        int resolvedHeadRoom =
                headRoomPercent != null ? headRoomPercent : MemoryKubernetesSizer.RECOMMENDED_MIN_HEADROOM_PERCENT;

        MemoryCalculationDto calculation = calculator.calculate(
                resolvedTotalBytes,
                resolvedThreads,
                liveClasses,
                resolvedHeadRoom,
                liveThreads,
                liveClasses,
                resolvedVirtualThreadsEnabled);
        Long detectedContainerMemoryLimitBytes =
                detectedContainerMemoryLimit.isPresent() ? detectedContainerMemoryLimit.getAsLong() : null;
        double maxRamPercentage = MemoryKubernetesSizer.heapPercentage(calculation);
        double initialRamPercentage = maxRamPercentage;
        String kubernetesJvmOptions =
                calculator.buildKubernetesJvmOptions(calculation, maxRamPercentage, initialRamPercentage);
        KubernetesMemoryRecommendationDto kubernetes = MemoryKubernetesSizer.recommend(
                calculation,
                heapUsage.getCommitted(),
                nonHeapUsage.getCommitted(),
                directBufferMemoryUsedBytes(),
                nativeMemoryTrackingEnabled(inputArgs),
                detectedContainerMemoryLimitBytes,
                maxRamPercentage,
                initialRamPercentage,
                kubernetesJvmOptions,
                resolvedKubernetesBurstableEnabled,
                resolvedKubernetesActuatorEnabled);

        return new LiveMemoryReport(heap, nonHeap, pools, inputArgs, calculation.jvmOptions(), calculation, kubernetes);
    }

    private boolean resolveVirtualThreadsEnabled() {
        if (environment == null || !environment.containsProperty(VIRTUAL_THREADS_PROPERTY)) {
            return false;
        }
        Boolean configured = environment.getProperty(VIRTUAL_THREADS_PROPERTY, Boolean.class);
        return configured != null && configured;
    }

    private boolean resolveKubernetesActuatorEnabled(Boolean kubernetesActuatorEnabled) {
        if (kubernetesActuatorEnabled != null) {
            return kubernetesActuatorEnabled;
        }
        if (environment == null) {
            return true;
        }
        boolean endpointsEnabledByDefault =
                environment.getProperty(ENDPOINTS_ENABLED_BY_DEFAULT_PROPERTY, Boolean.class, true);
        boolean healthEndpointEnabled =
                environment.getProperty(HEALTH_ENDPOINT_ENABLED_PROPERTY, Boolean.class, endpointsEnabledByDefault);
        if (!healthEndpointEnabled) {
            return false;
        }
        return environment.getProperty(HEALTH_PROBES_ENABLED_PROPERTY, Boolean.class, true);
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
