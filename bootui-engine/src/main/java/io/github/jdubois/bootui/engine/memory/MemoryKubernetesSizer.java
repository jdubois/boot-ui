package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import io.github.jdubois.bootui.spi.HealthProbeManifest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class MemoryKubernetesSizer {

    static final int DEFAULT_HEADROOM_PERCENT = 10;

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
            Long detectedContainerCurrentUsageBytes,
            double maxRamPercentage,
            double initialRamPercentage,
            String javaToolOptions,
            boolean burstableEnabled,
            boolean healthProbesEnabled,
            HealthProbeManifest healthProbeManifest) {

        long currentSnapshotBytes = estimateCurrentSnapshotBytes(
                heapCommittedBytes,
                nonHeapCommittedBytes,
                directBufferMemoryUsedBytes,
                detectedContainerCurrentUsageBytes);
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
                    healthProbesEnabled);
        }

        long limitBytes = calculation.totalMemoryBytes();
        long burstableRequestBytes = estimateBurstableRequestBytes(limitBytes, currentSnapshotBytes);
        long requestBytes = burstableEnabled ? burstableRequestBytes : limitBytes;
        List<String> warnings = buildWarnings(
                calculation,
                nativeMemoryTrackingEnabled,
                detectedContainerLimitBytes,
                detectedContainerCurrentUsageBytes,
                burstableRequestBytes,
                limitBytes,
                burstableEnabled,
                healthProbesEnabled,
                healthProbeManifest);
        String confidence = confidence(calculation, detectedContainerLimitBytes, detectedContainerCurrentUsageBytes);
        String qosClass = requestBytes < limitBytes ? "Burstable" : "Depends on CPU";
        String yaml = buildYaml(
                formatMi(requestBytes),
                formatMi(limitBytes),
                javaToolOptions,
                healthProbesEnabled,
                healthProbeManifest);

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
                healthProbesEnabled);
    }

    static double heapPercentage(MemoryCalculationDto calculation) {
        if (!calculation.valid() || calculation.totalMemoryBytes() <= 0) {
            return 0;
        }
        double calculated = calculation.heapBytes() * 100.0 / calculation.totalMemoryBytes();
        double floored = Math.floor(calculated * 1000.0) / 1000.0;
        return floored > 0 ? floored : calculated;
    }

    private static long estimateCurrentSnapshotBytes(
            long heapCommittedBytes,
            long nonHeapCommittedBytes,
            long directBufferMemoryUsedBytes,
            Long detectedContainerCurrentUsageBytes) {

        if (detectedContainerCurrentUsageBytes != null) {
            return nonNegative(detectedContainerCurrentUsageBytes);
        }
        long committedPools = saturatedAdd(nonNegative(heapCommittedBytes), nonNegative(nonHeapCommittedBytes));
        return saturatedAdd(committedPools, nonNegative(directBufferMemoryUsedBytes));
    }

    private static long estimateBurstableRequestBytes(long limitBytes, long currentSnapshotBytes) {
        long marginBytes =
                Math.max(MIN_SNAPSHOT_MARGIN_BYTES, Math.round(currentSnapshotBytes * SNAPSHOT_MARGIN_FACTOR));
        long snapshotWithMargin = currentSnapshotBytes > Long.MAX_VALUE - marginBytes
                ? Long.MAX_VALUE
                : currentSnapshotBytes + marginBytes;
        long requestWithFloor = Math.max(MIN_BURSTABLE_REQUEST_BYTES, snapshotWithMargin);
        if (requestWithFloor >= limitBytes) {
            return limitBytes;
        }
        long rounded = roundUpTo(requestWithFloor);
        return Math.min(limitBytes, rounded);
    }

    private static List<String> buildWarnings(
            MemoryCalculationDto calculation,
            boolean nativeMemoryTrackingEnabled,
            Long detectedContainerLimitBytes,
            Long detectedContainerCurrentUsageBytes,
            long burstableRequestBytes,
            long limitBytes,
            boolean burstableEnabled,
            boolean healthProbesEnabled,
            HealthProbeManifest healthProbeManifest) {

        List<String> warnings = new ArrayList<>();
        boolean memoryRequestBelowLimit = burstableEnabled && burstableRequestBytes < limitBytes;
        if (memoryRequestBelowLimit) {
            warnings.add(
                    "Because requests.memory is below limits.memory, this container prevents the Pod from receiving Guaranteed QoS.");
        } else {
            warnings.add(
                    "Memory request equals memory limit. Kubernetes Guaranteed QoS additionally requires equal, non-zero CPU request and limit for every container in the Pod.");
            if (burstableEnabled) {
                warnings.add(
                        "Burstable mode did not lower requests.memory because the current snapshot plus its safety margin reaches the selected limit.");
            }
        }
        if (!healthProbesEnabled) {
            warnings.add(healthProbeManifest.probesOmittedWarning());
        } else {
            warnings.add(
                    "Health probes use the framework's default paths and the named container port \"http\"; verify both against custom application or management-server settings.");
        }
        warnings.add(
                "JAVA_TOOL_OPTIONS uses MaxRAMPercentage, MinRAMPercentage, and InitialRAMPercentage so HotSpot follows the container limit across small and regular heaps. Metaspace, code cache, and thread-stack caps remain fixed.");
        warnings.add(
                "Direct memory is modeled at "
                        + formatMi(calculation.directMemoryBytes())
                        + " from the 10 MiB fallback and current direct-buffer usage, but is intentionally not hard-capped; validate Netty/NIO demand under representative load.");
        if (detectedContainerLimitBytes != null && detectedContainerLimitBytes.longValue() != limitBytes) {
            warnings.add("Detected cgroup memory limit is "
                    + formatMi(detectedContainerLimitBytes)
                    + ", which differs from the calculator total "
                    + formatMi(limitBytes)
                    + "; update the total memory input if you want the manifest to match the live container limit.");
        }
        if (!nativeMemoryTrackingEnabled) {
            warnings.add("Native Memory Tracking is not enabled; this model cannot attribute all native JVM memory.");
        } else {
            warnings.add(
                    "Native Memory Tracking is enabled, but BootUI does not consume its output; confidence remains model-based.");
        }
        if (calculation.threadCount() < calculation.liveThreadCount()) {
            warnings.add(
                    "Thread budget is below the current live thread count; increase it before applying these limits.");
        }
        if (burstableRequestBytes < limitBytes) {
            if (detectedContainerCurrentUsageBytes != null) {
                warnings.add(
                        "The burstable request starts from the current cgroup memory.current snapshot and can still be too low after warmup or a workload change.");
            } else {
                warnings.add(
                        "cgroup memory.current is unavailable, so the burstable request falls back to committed JVM pools plus observed direct buffers and can be too low after warmup.");
            }
        }
        return warnings;
    }

    private static String confidence(
            MemoryCalculationDto calculation,
            Long detectedContainerLimitBytes,
            Long detectedContainerCurrentUsageBytes) {
        if (!calculation.valid()) {
            return "Low";
        }
        if (detectedContainerLimitBytes != null
                && detectedContainerLimitBytes.longValue() == calculation.totalMemoryBytes()
                && detectedContainerCurrentUsageBytes != null) {
            return "Medium";
        }
        return "Low";
    }

    private static String buildYaml(
            String requestMemory,
            String limitMemory,
            String javaToolOptions,
            boolean healthProbesEnabled,
            HealthProbeManifest healthProbeManifest) {
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
                .append(javaToolOptions);
        if (healthProbesEnabled) {
            // Terminate the JAVA_TOOL_OPTIONS value line before the (optional) enabling env entry or the
            // probe stanzas, so frameworks without an enabling env var (e.g. Quarkus) still yield valid YAML.
            yaml.append("\n");
            if (healthProbeManifest.enablingEnvVar() != null) {
                // The value is always literal "true"; only the variable name is framework-specific today.
                yaml.append("  - name: ")
                        .append(healthProbeManifest.enablingEnvVar())
                        .append("\n")
                        .append("    value: \"true\"\n");
            }
            yaml.append("startupProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: ")
                    .append(healthProbeManifest.startupPath())
                    .append("\n")
                    .append("    port: http\n")
                    .append("  failureThreshold: 30\n")
                    .append("  periodSeconds: 10\n")
                    .append("readinessProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: ")
                    .append(healthProbeManifest.readinessPath())
                    .append("\n")
                    .append("    port: http\n")
                    .append("  periodSeconds: 10\n")
                    .append("  timeoutSeconds: 5\n")
                    .append("  failureThreshold: 3\n")
                    .append("livenessProbe:\n")
                    .append("  httpGet:\n")
                    .append("    path: ")
                    .append(healthProbeManifest.livenessPath())
                    .append("\n")
                    .append("    port: http\n")
                    .append("  periodSeconds: 15\n")
                    .append("  timeoutSeconds: 5\n")
                    .append("  failureThreshold: 3");
        }
        return yaml.toString();
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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
        return BigDecimal.valueOf(percentage).stripTrailingZeros().toPlainString();
    }
}
