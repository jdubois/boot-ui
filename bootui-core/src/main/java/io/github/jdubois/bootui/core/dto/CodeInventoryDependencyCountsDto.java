package io.github.jdubois.bootui.core.dto;

/**
 * Code Inventory's dependency counts: the declared dependencies, matched to the jars the agent saw define classes.
 *
 * @param declared the application's declared dependencies
 * @param loaded jars that defined at least one class in this run
 * @param loadedEarlier jars whose classes were all loaded before this run's claim, at JVM startup or in a previous run
 * @param notLoaded declared dependencies with no class loaded in this run
 * @param undeclared jars that loaded classes but match no declared dependency
 * @param declaredReason why the declared dependencies could not be read, or {@code null}
 */
public record CodeInventoryDependencyCountsDto(
        int declared, int loaded, int loadedEarlier, int notLoaded, int undeclared, String declaredReason) {}
