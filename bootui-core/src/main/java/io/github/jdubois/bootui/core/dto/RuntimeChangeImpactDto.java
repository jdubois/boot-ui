package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What a change to one symbol reaches in this run ({@code docs/PLAN-v2.md} §5.7): a checklist of what was and was not
 * exercised, never a verdict that a change is safe. Structural reach is a count, kept apart from observed execution,
 * since a route's traffic does not prove that a request went through the changed code.
 *
 * @param status {@code RESOLVED}; {@code AMBIGUOUS} with its candidates; {@code NOT_FOUND}; or {@code UNAVAILABLE}
 * @param reason why the status is not {@code RESOLVED}, or {@code null}
 * @param symbol the symbol asked for
 * @param node the node it resolved to, such as {@code REPOSITORY productRepository}, or {@code null}
 * @param candidates the nodes an ambiguous symbol names, at most {@value #MAX_ROWS}
 * @param structuralReach how many nodes reach the changed code through the bean graph or observed access, within five
 *     steps
 * @param observed the routes that reach it and ran in this run, most requests first
 * @param observedTotal how many there are, beyond those listed
 * @param notExercised the mapped routes that reach it and did not run
 * @param notExercisedTotal how many there are
 * @param sharedResources the routes outside its reach that use a table, cache, or host it writes or calls
 * @param sharedResourcesTotal how many there are
 * @param limitations what the impact cannot see
 * @param notExercisedUndetermined whether some reached routes cannot be classified: after aggregate overflow, or, for a
 *     method, routes that ran without their call trees showing it ({@code notObserved})
 * @param observedFrom how observed routes were found: {@code STRUCTURE} (the bean graph and route traffic),
 *     {@code HANDLER_MAPPING} (the routes mapped to a handler method), or {@code ROUTE_TREES} (the routes whose requests'
 *     own call trees executed the method); {@code null} unless resolved
 * @param methods for a method, the method keys it names, {@code class#name+descriptor}, at most {@value #MAX_ROWS}
 * @param methodStatus for a method, whether it ran in this run per Code Inventory: {@code EXECUTED},
 *     {@code NEVER_EXECUTED}, {@code NOT_TRACKED}, or {@code null} when unknown
 * @param notObserved for a method, the routes that reach it and ran in this run while their call trees did not show it:
 *     never proof that it did not run, each with why
 * @param notObservedTotal how many there are
 */
public record RuntimeChangeImpactDto(
        String status,
        String reason,
        String symbol,
        String node,
        List<String> candidates,
        long structuralReach,
        List<RuntimeImpactRouteDto> observed,
        int observedTotal,
        List<RuntimeImpactRouteDto> notExercised,
        int notExercisedTotal,
        List<RuntimeImpactRouteDto> sharedResources,
        int sharedResourcesTotal,
        List<String> limitations,
        boolean notExercisedUndetermined,
        String observedFrom,
        List<String> methods,
        String methodStatus,
        List<RuntimeImpactRouteDto> notObserved,
        int notObservedTotal) {

    public static final String FROM_STRUCTURE = "STRUCTURE";
    public static final String FROM_HANDLER_MAPPING = "HANDLER_MAPPING";
    public static final String FROM_ROUTE_TREES = "ROUTE_TREES";

    /** The rows each list holds at most. */
    public static final int MAX_ROWS = 8;

    public RuntimeChangeImpactDto {
        candidates = DtoCollections.immutableCopy(candidates);
        observed = DtoCollections.immutableCopy(observed);
        notExercised = DtoCollections.immutableCopy(notExercised);
        sharedResources = DtoCollections.immutableCopy(sharedResources);
        limitations = DtoCollections.immutableCopy(limitations);
        methods = DtoCollections.immutableCopy(methods);
        notObserved = DtoCollections.immutableCopy(notObserved);
    }

    public RuntimeChangeImpactDto(
            String status,
            String reason,
            String symbol,
            String node,
            List<String> candidates,
            long structuralReach,
            List<RuntimeImpactRouteDto> observed,
            int observedTotal,
            List<RuntimeImpactRouteDto> notExercised,
            int notExercisedTotal,
            List<RuntimeImpactRouteDto> sharedResources,
            int sharedResourcesTotal,
            List<String> limitations,
            boolean notExercisedUndetermined) {
        this(
                status,
                reason,
                symbol,
                node,
                candidates,
                structuralReach,
                observed,
                observedTotal,
                notExercised,
                notExercisedTotal,
                sharedResources,
                sharedResourcesTotal,
                limitations,
                notExercisedUndetermined,
                null,
                List.of(),
                null,
                List.of(),
                0);
    }

    public RuntimeChangeImpactDto(
            String status,
            String reason,
            String symbol,
            String node,
            List<String> candidates,
            long structuralReach,
            List<RuntimeImpactRouteDto> observed,
            int observedTotal,
            List<RuntimeImpactRouteDto> notExercised,
            int notExercisedTotal,
            List<RuntimeImpactRouteDto> sharedResources,
            int sharedResourcesTotal,
            List<String> limitations) {
        this(
                status,
                reason,
                symbol,
                node,
                candidates,
                structuralReach,
                observed,
                observedTotal,
                notExercised,
                notExercisedTotal,
                sharedResources,
                sharedResourcesTotal,
                limitations,
                false);
    }
}
