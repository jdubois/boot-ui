package io.github.jdubois.bootui.core.dto;

/**
 * One jar in Code Inventory's dependency use: a declared dependency, a code source the agent saw define classes, or
 * both, matched by {@code groupId:artifactId} from the jar's Maven metadata, else by its file name.
 *
 * @param jar the jar's file name, or {@code null} for a declared dependency no class came from
 * @param groupId its Maven group, when known
 * @param artifactId its Maven artifact, when known
 * @param version its version, when known
 * @param declared whether the application declares it
 * @param status {@code LOADED} (classes defined in this run), {@code LOADED_EARLIER} (classes loaded only before this
 *     run's claim, at JVM startup or in a previous run), or {@code NOT_LOADED} (no class in this run, never "unused")
 * @param classesLoaded classes it defined in this run
 * @param classesLoadedTotal classes it defined since the agent started, including before the first claim
 * @param loadedAt {@code STARTUP} or {@code AFTER_STARTUP} for its first class in this run, or {@code null}
 * @param firstLoadEpochMillis when its first class in this run loaded, or {@code null}
 * @param firstRoute the route of the request that loaded its first class in this run, or {@code null}
 * @param firstRequestId that request, or {@code null}
 */
public record CodeInventoryDependencyDto(
        String jar,
        String groupId,
        String artifactId,
        String version,
        boolean declared,
        String status,
        long classesLoaded,
        long classesLoadedTotal,
        String loadedAt,
        Long firstLoadEpochMillis,
        String firstRoute,
        String firstRequestId) {}
