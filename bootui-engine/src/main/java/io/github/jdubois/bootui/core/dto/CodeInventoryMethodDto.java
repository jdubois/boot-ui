package io.github.jdubois.bootui.core.dto;

/**
 * One application method in Code Inventory.
 *
 * @param key {@code className#name+descriptor}, as the agent keys it
 * @param packageName its package
 * @param className its class's binary name
 * @param name its name, {@code <init>} for a constructor
 * @param descriptor its JVM descriptor
 * @param status {@code EXECUTED}, {@code NEVER_EXECUTED}, {@code NOT_TRACKED}, or {@code GENERATED} (ran, with no
 *     class file on disk)
 * @param notTrackedReason why the agent could not see it, for {@code NOT_TRACKED}
 * @param change {@code CHANGED} or {@code ADDED} since the previous run, or {@code null}
 * @param firstRequestId the request that ran it first in this run, or {@code null} (at startup, or not recorded)
 * @param firstRoute that request's route, or {@code null}
 * @param firstHitEpochMillis when it first ran in this run, when recorded
 */
public record CodeInventoryMethodDto(
        String key,
        String packageName,
        String className,
        String name,
        String descriptor,
        String status,
        String notTrackedReason,
        String change,
        String firstRequestId,
        String firstRoute,
        Long firstHitEpochMillis) {}
