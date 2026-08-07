package io.github.jdubois.bootui.core.dto;

/**
 * Result of the Paketo-style memory calculator.
 *
 * <p>Computed by partitioning a target container memory budget into JVM regions:
 * {@code heap = totalMemory − headRoom − directMemory − metaspace − codeCache − stack×threads}.
 * Mirrors the formula in {@code paketo-buildpacks/libjvm/calc/calculator.go} with live-observation
 * adaptations: the live loaded-class count replaces the buildpack's build-time JAR-entry estimate,
 * direct memory is raised to at least current direct-buffer usage, and virtual-thread detection does
 * not reduce the conservative platform-thread stack reserve.
 */
public record MemoryCalculationDto(
        long totalMemoryBytes,
        long heapBytes,
        long metaspaceBytes,
        long codeCacheBytes,
        long directMemoryBytes,
        long stackBytesPerThread,
        long stackBytesTotal,
        long headRoomBytes,
        long fixedRegionsBytes,
        int threadCount,
        int loadedClasses,
        int liveThreadCount,
        int liveLoadedClassCount,
        int headRoomPercent,
        boolean virtualThreadsEnabled,
        String virtualThreadsProperty,
        String jvmOptions,
        boolean valid,
        String error) {}
