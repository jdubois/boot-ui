package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Live CRaC runtime status for the host application, read from the classpath, the JVM input
 * arguments, and Spring framework properties. Resource caveats reuse the last explicit scan's
 * inventory. No observation certifies operational checkpoint/restore support.
 *
 * @param cracApiPresent whether the {@code org.crac} API is on the classpath
 * @param cracCapableJvm whether a JVM CRaC implementation marker was observed; this legacy field
 *     name does not certify engine availability, real images or successful checkpoint/restore
 * @param jvmName the reported {@code java.vm.name} of the running JVM
 * @param checkpointOnRefresh whether the exact {@code spring.context.checkpoint=onRefresh} value
 *     is configured through SpringProperties; the one-shot request may already have been consumed
 * @param checkpointTo the {@code -XX:CRaCCheckpointTo} directory, or {@code null} when not set
 * @param restoreFrom the {@code -XX:CRaCRestoreFrom} directory, or {@code null} when not set
 * @param cracJvmArgs bounded, exposure-policy-filtered arguments from the explicit CRaC option allowlist
 * @param summary a short human-readable summary of the current CRaC readiness state
 * @param restoreCaveats evidence limitations and review prompts, including unavailable observations
 */
public record CracRuntimeStatusDto(
        boolean cracApiPresent,
        boolean cracCapableJvm,
        String jvmName,
        boolean checkpointOnRefresh,
        String checkpointTo,
        String restoreFrom,
        List<String> cracJvmArgs,
        String summary,
        List<String> restoreCaveats) {

    public CracRuntimeStatusDto {
        cracJvmArgs = DtoCollections.immutableCopy(cracJvmArgs);
        restoreCaveats = DtoCollections.immutableCopy(restoreCaveats);
    }
}
