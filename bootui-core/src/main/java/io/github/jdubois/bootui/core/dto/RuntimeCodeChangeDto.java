package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One method changed or added since the previous run, in a run comparison led by code changes ({@code docs/PLAN-v2.md}
 * §5.8, §5.17, M5-7a).
 *
 * @param key {@code className#name+descriptor}, as the agent keys it
 * @param className its class's binary name
 * @param name its name, {@code <init>} for a constructor
 * @param descriptor its JVM descriptor
 * @param change {@code CHANGED} or {@code ADDED}
 * @param status whether it ran in this run per Code Inventory: {@code EXECUTED}, {@code NEVER_EXECUTED}, {@code
 *     NOT_TRACKED}, or {@code GENERATED}
 * @param notTrackedReason why the agent could not see it, for {@code NOT_TRACKED}
 * @param routes the routes whose requests executed it in this run, from their own call trees or Code Inventory's first
 *     request, at most {@value #MAX_ROUTES}
 * @param routesTotal how many there are
 * @param routesNote why its routes are unknown or incomplete, or {@code null}
 */
public record RuntimeCodeChangeDto(
        String key,
        String className,
        String name,
        String descriptor,
        String change,
        String status,
        String notTrackedReason,
        List<String> routes,
        int routesTotal,
        String routesNote) {

    /** The routes each method lists at most. */
    public static final int MAX_ROUTES = 8;

    public RuntimeCodeChangeDto {
        routes = DtoCollections.immutableCopy(routes);
    }
}
