package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryCalculationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MemoryKubernetesSizer {

    static final int RECOMMENDED_MIN_HEADROOM_PERCENT = 10;
    static final int RECOMMENDED_MAX_HEADROOM_PERCENT = 15;
    static final double MAX_HEAP_PERCENTAGE = 75.0;
    static final double RECOMMENDED_MIN_HEAP_PERCENTAGE = 65.0;

    private static final long MB = 1024L * 1024L;
    private static final long MIN_BURSTABLE_REQUEST_BYTES = 128L * MB;
    private static final long REQUEST_GRANULARITY_BYTES = 64L * MB;
    private static final long MIN_SNAPSHOT_MARGIN_BYTES = 64L * MB;
    private static final double SNAPSHOT_MARGIN_FACTOR = 0.15;

    private MemoryKubernetesSizer() {}

    static KubernetesMemoryRecommendationDto recommend(
            MemoryCalculationDto calculation,
            long heapCommittedBytes,
            long nonHeapCommittedBytes,
            long directBufferMemoryUsedBytes,
            boolean nativeMemoryTrackingEnabled,
            Long detectedContainerLimitBytes,
            double maxRamPercentage,
            double initialRamPercentage,
            String javaToolOptions,
            boolean burstableEnabled,
            boolean actuatorProbesEnabled) {

        long currentSnapshotBytes = estimateCurrentSnapshotBytes(
                calculation, heapCommittedBytes, nonHeapCommittedBytes, directBufferMemoryUsedBytes);
        String detectedContainerLimitMemory =
                detectedContainerLimitBytes == null ? null : formatMi(detectedContainerLimitBytes);

        if (!calculation.valid()) {
            List<String> warnings = new ArrayList<>();
            warnings.add(calculation.error());
            return new KubernetesMemoryRecommendationDto(
                    0,
                    calculation.totalMemoryBytes(),
                    0,
                    currentSnapshotBytes,
                    detectedContainerLimitBytes,
                    "",
                    formatMi(calculation.totalMemoryBytes()),
                    "",
                    formatMi(currentSnapshotBytes),
                    detectedContainerLimitMemory,
                    "Unavailable",
                    "Low",
                    List.copyOf(warnings),
                    "",
                    0,
                    0,
                    "",
                    burstableEnabled,
                    actuatorProbesEnabled);
        }

        long limitBytes = calculation.totalMemoryBytes();
        long burstableRequestBytes = estimateBurstableRequestBytes(limitBytes, currentSnapshotBytes);
        long requestBytes = burstableEnabled ? burstableRequestBytes : limitBytes;
        List<String> warnings = buildWarnings(
                calculation,
                nativeMemoryTrackingEnabled,
                detectedContainerLimitBytes,
                burstableRequestBytes,
                limitBytes,
                maxRamPercentage,
                javaToolOptions,
                burstableEnabled,
                actuatorProbesEnabled);
        String confidence = confidence(calculation, nativeMemoryTrackingEnabled, detectedContainerLimitBytes);
        String qosClass = requestBytes < limitBytes ? "Burstable" : "Guaranteed";
        String yaml = buildYaml(formatMi(requestBytes), formatMi(limitBytes), javaToolOptions, actuatorProbesEnabled);

        return new KubernetesMemoryRecommendationDto(
                requestBytes,
                limitBytes,
                burstableRequestBytes,
                currentSnapshotBytes,
                detectedContainerLimitBytes,
                formatMi(requestBytes),
                formatMi(limitBytes),
                formatMi(burstableRequestBytes),
                formatMi(currentSnapshotBytes),
                detectedContainerLimitMemory,
                qosClass,
                confidence,
                List.copyOf(warnings),
                yaml,
                maxRamPercentage,
                initialRamPercentage,
                javaToolOptions,
                burstableEnabled,
                actuatorProbesEnabled);
    }

    static double heapPercentage(MemoryCalculationDto calculation) {
        if (!calculation.valid() || calculation.totalMemoryBytes() <= 0) {
            return 0;
        }
        double calculated = calculation.heapBytes() * 100.0 / calculation.totalMemoryBytes();
        return Math.max(1.0, Math.min(MAX_HEAP_PERCENTAGE, calculated));
    }

    private static long estimateCurrentSnapshotBytes(
            MemoryCalculationDto calculation,
            long heapCommittedBytes,
            long nonHeapCommittedBytes,
            long directBufferMemoryUsedBytes) {

        long liveStackBytes = calculation.stackBytesPerThread() * Math.max(0L, calculation.liveThreadCount());
        return nonNegative(heapCommittedBytes)
                + nonNegative(nonHeapCommittedBytes)
                + nonNegative(directBufferMemoryUsedBytes)
                + liveStackBytes;
    }

    private static long estimateBurstableRequestBytes(long limitBytes, long currentSnapshotBytes) {
        long marginBytes =
                Math.max(MIN_SNAPSHOT_MARGIN_BYTES, Math.round(currentSnapshotBytes * SNAPSHOT_MARGIN_FACTOR));
        long rounded = roundUpTo(Math.max(MIN_BURSTABLE_REQUEST_BYTES, currentSnapshotBytes + marginBytes));
        return Math.min(limitBytes, rounded);
    }

    private static List<String> buildWarnings(
            MemoryCalculationDto calculation,
            boolean nativeMemoryTrackingEnabled,
            Long detectedContainerLimitBytes,
            long burstableRequestBytes,
            long limitBytes,
            double maxRamPercentage,
            String javaToolOptions,
            boolean burstableEnabled,
            boolean actuatorProbesEnabled) {

        List<String> warnings = new ArrayList<>();
        warnings.add(garbageCollectorWarning(javaToolOptions));
        if (burstableEnabled) {
            warnings.add(
                    "Burstable mode lowers requests.memory below limits.memory; use it only in clusters that intentionally overcommit memory.");
        } else {
            warnings.add(
                    "Request equals limit for Kubernetes Guaranteed QoS; enable burstable mode only in clusters that intentionally overcommit memory.");
        }
        if (!actuatorProbesEnabled) {
            warnings.add(
                    "Spring Boot Actuator probes are omitted from the snippet; enabling them is recommended so Kubernetes can restart or drain unhealthy pods.");
        }
        warnings.add(
                "JAVA_TOOL_OPTIONS uses MaxRAMPercentage/InitialRAMPercentage so the heap follows the container memory limit; fixed metaspace, code cache, direct memory, and stack caps must still fit if you shrink the pod.");
        if (detectedContainerLimitBytes != null && detectedContainerLimitBytes.longValue() != limitBytes) {
            warnings.add("Detected cgroup memory limit is "
                    + formatMi(detectedContainerLimitBytes)
                    + ", which differs from the calculator total "
                    + formatMi(limitBytes)
                    + "; update the total memory input if you want the manifest to match the live container limit.");
        }
        if (calculation.headRoomPercent() < RECOMMENDED_MIN_HEADROOM_PERCENT) {
            warnings.add("Headroom below "
                    + RECOMMENDED_MIN_HEADROOM_PERCENT
                    + "% leaves little room for native allocations; Kubernetes deployments usually start at "
                    + RECOMMENDED_MIN_HEADROOM_PERCENT
                    + "-"
                    + RECOMMENDED_MAX_HEADROOM_PERCENT
                    + "% unless measured otherwise.");
        }
        if (!nativeMemoryTrackingEnabled) {
            warnings.add(
                    "Native Memory Tracking is not enabled, so native overhead is estimated from JVM pools and runtime defaults.");
        }
        if (calculation.threadCount() < calculation.liveThreadCount()) {
            warnings.add(
                    "Thread budget is below the current live thread count; increase it before applying these limits.");
        }
        double calculatedHeapPercentage = calculation.heapBytes() * 100.0 / limitBytes;
        if (calculatedHeapPercentage > MAX_HEAP_PERCENTAGE) {
            warnings.add("Kubernetes heap sizing is capped at "
                    + formatPercentage(MAX_HEAP_PERCENTAGE)
                    + "% of the container limit to leave room for native memory.");
        } else if (maxRamPercentage < RECOMMENDED_MIN_HEAP_PERCENTAGE) {
            warnings.add(
                    "Heap is "
                            + formatPercentage(maxRamPercentage)
                            + "% of the container limit because fixed non-heap and thread-stack reservations are high for this memory size.");
        }
        if (burstableRequestBytes < limitBytes) {
            warnings.add(
                    "The burstable request is based on the current committed-memory snapshot and can be too low after warmup.");
        }
        return warnings;
    }

    private static String garbageCollectorWarning(String javaToolOptions) {
        if (javaToolOptions != null && javaToolOptions.contains("-XX:+UseZGC")) {
            String mode = javaToolOptions.contains("-XX:+ZGenerational") ? " with generational mode" : "";
            return "Garbage collector: ZGC"
                    + mode
                    + " is selected for calculated heaps of 4 GiB or more to prioritize low pause times.";
        }
        if (javaToolOptions != null && javaToolOptions.contains("-XX:+UseG1GC")) {
            return "Garbage collector: G1GC is selected for calculated heaps below 4 GiB; the advisor switches to ZGC for larger heaps.";
        }
        return "Garbage collector: unavailable until the JVM options can be calculated.";
    }

    private static String confidence(
            MemoryCalculationDto calculation, boolean nativeMemoryTrackingEnabled, Long detectedContainerLimitBytes) {
        if (!calculation.valid()) {
            return "Low";
        }
        if (detectedContainerLimitBytes != null
                && detectedContainerLimitBytes.longValue() == calculation.totalMemoryBytes()
                && nativeMemoryTrackingEnabled
                && calculation.headRoomPercent() >= RECOMMENDED_MIN_HEADROOM_PERCENT) {
            return "High";
        }
        return "Medium";
    }

    private static String buildYaml(
            String requestMemory, String limitMemory, String javaToolOptions, boolean actuatorProbesEnabled) {
        String escapedJvmOptions = javaToolOptions.replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder yaml = new StringBuilder(512);
        yaml.append("resources:\n")
                .append("  requests:\n")
                .append("    memory: \"")
                .append(requestMemory)
                .append("\"\n")
                .append("  limits:\n")
                .append("    memory: \"")
                .append(limitMemory)
                .append("\"\n")
                .append("env:\n")
                .append("  - name: JAVA_TOOL_OPTIONS\n")
                .append("    value: >-\n")
                .append("      ")
                .append(escapedJvmOptions);
        if (actuatorProbesEnabled) {
            yaml.append("\n")
                    .append("  - name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED\n")
                    .append("    value: \"true\"\n")
                    .append("startupProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: /actuator/health/liveness\n")
                    .append("    port: 8080\n")
                    .append("  failureThreshold: 30\n")
                    .append("  periodSeconds: 10\n")
                    .append("readinessProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: /actuator/health/readiness\n")
                    .append("    port: 8080\n")
                    .append("  periodSeconds: 10\n")
                    .append("  timeoutSeconds: 5\n")
                    .append("  failureThreshold: 3\n")
                    .append("livenessProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: /actuator/health/liveness\n")
                    .append("    port: 8080\n")
                    .append("  periodSeconds: 15\n")
                    .append("  timeoutSeconds: 5\n")
                    .append("  failureThreshold: 3");
        }
        return yaml.toString();
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static long roundUpTo(long value) {
        if (value <= 0) {
            return REQUEST_GRANULARITY_BYTES;
        }
        return ((value + REQUEST_GRANULARITY_BYTES - 1) / REQUEST_GRANULARITY_BYTES) * REQUEST_GRANULARITY_BYTES;
    }

    static String formatMi(long bytes) {
        long mebibytes = Math.max(0, (bytes + MB - 1) / MB);
        return mebibytes + "Mi";
    }

    private static String formatPercentage(double percentage) {
        return String.format(Locale.ROOT, "%.1f", percentage);
    }
}
