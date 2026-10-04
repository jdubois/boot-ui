package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One route in a change impact ({@code docs/PLAN-v2.md} §5.7): one that ran through the changed code, one mapped through
 * it that did not run, or one that shares a resource with it.
 *
 * @param route its label, such as {@code GET /api/orders/{id}}
 * @param requests the requests it served in this run
 * @param anonymous those an authorization decision proved anonymous
 * @param errors those answered with a {@code 5xx} status
 * @param exemplarRequestIds up to three of its retained requests, to open in Live Activity
 * @param reads the tables and caches it read
 * @param writes the tables and caches it wrote
 * @param shared the resources it shares with the changed code, for a route that shares one
 * @param check what to do about it, for a route that did not run
 * @param executedRequests for a method's impact, at least how many of its requests executed the method, from its own
 *     call trees or Code Inventory's first request; else 0
 * @param partial for a method's impact, whether its call trees may miss or under-count what its requests executed
 */
public record RuntimeImpactRouteDto(
        String route,
        long requests,
        long anonymous,
        long errors,
        List<String> exemplarRequestIds,
        List<String> reads,
        List<String> writes,
        List<String> shared,
        String check,
        long executedRequests,
        boolean partial) {

    public RuntimeImpactRouteDto {
        exemplarRequestIds = DtoCollections.immutableCopy(exemplarRequestIds);
        reads = DtoCollections.immutableCopy(reads);
        writes = DtoCollections.immutableCopy(writes);
        shared = DtoCollections.immutableCopy(shared);
    }

    public RuntimeImpactRouteDto(
            String route,
            long requests,
            long anonymous,
            long errors,
            List<String> exemplarRequestIds,
            List<String> reads,
            List<String> writes,
            List<String> shared,
            String check) {
        this(route, requests, anonymous, errors, exemplarRequestIds, reads, writes, shared, check, 0L, false);
    }
}
