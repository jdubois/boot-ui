package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A group of outbound HTTP calls to the same endpoint captured by the REST Client panel, used to
 * surface repeated calls and likely "chatty" access patterns (the REST analog of a SQL N+1 query).
 *
 * @param method the HTTP method shared by the grouped calls
 * @param host the target host shared by the grouped calls
 * @param path the request path with numeric-id/UUID segments normalized to {@code {id}} so calls to, e.g.,
 *     {@code /orders/1}, {@code /orders/2}, and {@code /orders/3} group under {@code /orders/{id}}
 * @param executions number of buffered calls to this method/host/path
 * @param totalDurationMillis sum of call times across the grouped calls
 * @param maxDurationMillis slowest call time within the group
 * @param chatty whether the repetition count suggests a chatty (N+1-style) outbound-call pattern; unlike
 *     the SQL panel's SELECT-only rule, any HTTP method can be flagged since repeating any call in a loop
 *     is costly
 * @param callSites distinct call sites observed for this group's calls, most-recently-seen first and
 *     bounded to a handful of entries; empty when call-site capture is disabled or no application frame
 *     was found for any call in the group
 */
public record RestClientTraceGroupDto(
        String method,
        String host,
        String path,
        long executions,
        long totalDurationMillis,
        long maxDurationMillis,
        boolean chatty,
        List<String> callSites) {

    public RestClientTraceGroupDto {
        callSites = callSites == null ? List.of() : List.copyOf(callSites);
    }
}
