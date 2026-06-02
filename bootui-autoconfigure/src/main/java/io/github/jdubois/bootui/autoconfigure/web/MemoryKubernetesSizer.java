package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.BootUiDtos.MemoryCalculationDto;
import java.util.ArrayList;
import java.util.List;

final class MemoryKubernetesSizer {

    static final int RECOMMENDED_MIN_HEADROOM_PERCENT = 10;
    static final int RECOMMENDED_MAX_HEADROOM_PERCENT = 15;

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
            Long detectedContainerLimitBytes) {

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
                    "");
        }

        long limitBytes = calculation.totalMemoryBytes();
        long requestBytes = limitBytes;
        long burstableRequestBytes = estimateBurstableRequestBytes(limitBytes, currentSnapshotBytes);
        List<String> warnings = buildWarnings(
                calculation,
                nativeMemoryTrackingEnabled,
                detectedContainerLimitBytes,
                burstableRequestBytes,
                limitBytes);
        String confidence = confidence(calculation, nativeMemoryTrackingEnabled, detectedContainerLimitBytes);
        String yaml = buildYaml(formatMi(requestBytes), formatMi(limitBytes), calculation.jvmOptions());

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
                "Guaranteed",
                confidence,
                List.copyOf(warnings),
                yaml);
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
            long limitBytes) {

        List<String> warnings = new ArrayList<>();
        warnings.add(
                "Request equals limit for Kubernetes Guaranteed QoS; use the burstable request only in clusters that intentionally overcommit memory.");
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
        if (calculation.heapBytes() * 100.0 / limitBytes > 75.0) {
            warnings.add("Heap is above 75% of the container limit; verify native memory under representative load.");
        }
        if (burstableRequestBytes < limitBytes) {
            warnings.add(
                    "The burstable request is based on the current committed-memory snapshot and can be too low after warmup.");
        }
        warnings.add(
                "MALLOC_ARENA_MAX=2 is set to curb glibc native-memory fragmentation on standard (Debian/Ubuntu) images; omit it on musl-based images such as Alpine.");
        return warnings;
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

    private static String buildYaml(String requestMemory, String limitMemory, String jvmOptions) {
        String escapedJvmOptions = jvmOptions.replace("\\", "\\\\").replace("\"", "\\\"");
        return "resources:\n"
                + "  requests:\n"
                + "    memory: \""
                + requestMemory
                + "\"\n"
                + "  limits:\n"
                + "    memory: \""
                + limitMemory
                + "\"\n"
                + "env:\n"
                + "  - name: JAVA_TOOL_OPTIONS\n"
                + "    value: \""
                + escapedJvmOptions
                + "\"\n"
                + "  - name: MALLOC_ARENA_MAX\n"
                + "    value: \"2\"";
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
}
