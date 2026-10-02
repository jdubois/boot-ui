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
        List<String> limitations) {

    /** The rows each list holds at most. */
    public static final int MAX_ROWS = 8;

    public RuntimeChangeImpactDto {
        candidates = DtoCollections.immutableCopy(candidates);
        observed = DtoCollections.immutableCopy(observed);
        notExercised = DtoCollections.immutableCopy(notExercised);
        sharedResources = DtoCollections.immutableCopy(sharedResources);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
