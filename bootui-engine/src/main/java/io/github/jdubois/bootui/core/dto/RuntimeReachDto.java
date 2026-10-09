package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Whether a dependency's code, or a class an advisory names, loaded in this JVM, from the BootUI agent's inventory
 * sensor ({@code docs/PLAN-v2.md} §5.15, M5-9a). A prioritization hint only: it never changes a finding's severity,
 * score, or Scorecard penalty, since a class not loaded in this run may load in another, or later in this one.
 *
 * @param status {@link #NOT_LOADED} (no class of the artifact loaded, and the evidence is complete),
 *     {@link #LOADED}, {@link #AFFECTED_CLASS_LOADED} (a class the advisory names loaded from the artifact), or
 *     {@link #UNKNOWN}, never {@link #NOT_LOADED} when the evidence cannot show it
 * @param reason why it is {@link #UNKNOWN}, or what qualifies the status (loaded only in an earlier run, only by
 *     BootUI's own work, named classes not all known), or {@code null}
 * @param classesLoaded the artifact's classes the application loaded in this run
 * @param classesLoadedTotal the artifact's classes loaded in this JVM since the agent recorded, by anyone
 * @param loadedThisRun whether the application loaded one of its classes in this run
 * @param firstRoute the route of the request that first loaded one of its classes in this run, or {@code null} when
 *     none did or while the HTTP Exchanges panel is not visible
 * @param loadedClasses the classes the advisory names that loaded, for {@link #AFFECTED_CLASS_LOADED}
 */
public record RuntimeReachDto(
        String status,
        String reason,
        long classesLoaded,
        long classesLoadedTotal,
        boolean loadedThisRun,
        String firstRoute,
        List<String> loadedClasses) {

    public static final String NOT_LOADED = "NOT_LOADED";
    public static final String LOADED = "LOADED";
    public static final String AFFECTED_CLASS_LOADED = "AFFECTED_CLASS_LOADED";
    public static final String UNKNOWN = "UNKNOWN";

    public RuntimeReachDto {
        loadedClasses = DtoCollections.immutableCopy(loadedClasses);
    }

    /** An unknown reach, with why. */
    public static RuntimeReachDto unknown(String reason) {
        return new RuntimeReachDto(UNKNOWN, reason, 0L, 0L, false, null, List.of());
    }
}
