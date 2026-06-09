package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Live CRaC runtime status for the host application, read from the classpath, the JVM input
 * arguments, and the Spring {@code Environment}. This drives the read-only status section of the
 * CRaC panel and is independent of the on-demand readiness scan.
 *
 * @param cracApiPresent whether the {@code org.crac} API is on the classpath
 * @param cracCapableJvm whether the running JVM provides a real CRaC implementation (a CRaC-enabled
 *     JDK such as Azul Zulu CRaC or BellSoft Liberica) rather than the no-op shim
 * @param jvmName the reported {@code java.vm.name} of the running JVM
 * @param checkpointOnRefresh whether {@code spring.context.checkpoint=onRefresh} requests an
 *     automatic checkpoint once the context is refreshed
 * @param checkpointTo the {@code -XX:CRaCCheckpointTo} directory, or {@code null} when not set
 * @param restoreFrom the {@code -XX:CRaCRestoreFrom} directory, or {@code null} when not set
 * @param cracJvmArgs the CRaC-related JVM input arguments detected for the running process
 * @param summary a short human-readable summary of the current CRaC readiness state
 */
public record CracRuntimeStatusDto(
        boolean cracApiPresent,
        boolean cracCapableJvm,
        String jvmName,
        boolean checkpointOnRefresh,
        String checkpointTo,
        String restoreFrom,
        List<String> cracJvmArgs,
        String summary) {}
