package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Symfony-style per-request profile: a single request correlated with the other signals it produced.
 *
 * <p>Assembled by joining BootUI's existing in-memory buffers around one HTTP exchange, strongest tier
 * first: a trace id carried by exactly one captured request, then the request's serving thread within
 * its window, then its time window. An adapter offers only the tiers it can prove (see
 * {@link #correlationTiers()}); a time-window match is heuristic, so {@link #approximate()} and, for SQL,
 * {@link #sqlCorrelationApproximate()} flag when it was used and the UI labels it as approximate.</p>
 *
 * <p>The sections after {@code notes} were added later and are additive: an older client ignores them,
 * and the {@linkplain #RequestProfileDto(boolean, String, HttpExchangeDto, List, List, boolean, List, List,
 * TraceDetailDto, RequestProfileTimingDto, List) original constructor} fills them with empty values.</p>
 *
 * @param available whether the request could be located and profiled
 * @param unavailableReason why the profile is unavailable, or {@code null}
 * @param request the request summary (reused, already-masked HTTP exchange)
 * @param sql the correlated SQL executions in execution order, bounded (see {@link #sections()})
 * @param sqlGroups grouped SQL statements with N+1 candidates flagged, over every correlated execution
 * @param sqlCorrelationApproximate whether the SQL association relied on the time-window heuristic
 * @param exceptions exceptions correlated to this request, bounded
 * @param security security audit events correlated to this request, bounded
 * @param trace the distributed trace for this request when a trace id matched, or {@code null}
 * @param timing coarse timing breakdown
 * @param notes human-readable notes about how correlation was performed and its caveats
 * @param restCalls outbound REST client calls correlated to this request in call order, bounded, and
 *     masked exactly as the REST Client panel shows them
 * @param cacheAccesses cache accesses correlated to this request in access order, bounded; only a
 *     hashed key is ever carried
 * @param sections how each child section was correlated: its availability, weakest tier used, total,
 *     truncation, and ambiguity counts
 * @param correlationTiers the correlation tiers this adapter can provide, with a reason for each one it
 *     cannot
 * @param approximate whether any correlated child was matched by the heuristic time-window tier
 */
public record RequestProfileDto(
        boolean available,
        String unavailableReason,
        HttpExchangeDto request,
        List<SqlTraceEntryDto> sql,
        List<SqlTraceGroupDto> sqlGroups,
        boolean sqlCorrelationApproximate,
        List<RequestProfileExceptionDto> exceptions,
        List<RequestProfileSecurityDto> security,
        TraceDetailDto trace,
        RequestProfileTimingDto timing,
        List<String> notes,
        List<RestClientTraceEntryDto> restCalls,
        List<RequestProfileCacheAccessDto> cacheAccesses,
        List<RequestProfileSectionDto> sections,
        List<RequestProfileTierDto> correlationTiers,
        boolean approximate) {

    public RequestProfileDto {
        sql = DtoCollections.immutableCopy(sql);
        sqlGroups = DtoCollections.immutableCopy(sqlGroups);
        exceptions = DtoCollections.immutableCopy(exceptions);
        security = DtoCollections.immutableCopy(security);
        notes = DtoCollections.immutableCopy(notes);
        restCalls = DtoCollections.immutableCopy(restCalls);
        cacheAccesses = DtoCollections.immutableCopy(cacheAccesses);
        sections = DtoCollections.immutableCopy(sections);
        correlationTiers = DtoCollections.immutableCopy(correlationTiers);
    }

    /** The original profile shape, without REST client, cache, section, or tier evidence. */
    public RequestProfileDto(
            boolean available,
            String unavailableReason,
            HttpExchangeDto request,
            List<SqlTraceEntryDto> sql,
            List<SqlTraceGroupDto> sqlGroups,
            boolean sqlCorrelationApproximate,
            List<RequestProfileExceptionDto> exceptions,
            List<RequestProfileSecurityDto> security,
            TraceDetailDto trace,
            RequestProfileTimingDto timing,
            List<String> notes) {
        this(
                available,
                unavailableReason,
                request,
                sql,
                sqlGroups,
                sqlCorrelationApproximate,
                exceptions,
                security,
                trace,
                timing,
                notes,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                sqlCorrelationApproximate);
    }

    public static RequestProfileDto unavailable(String reason) {
        return new RequestProfileDto(
                false, reason, null, List.of(), List.of(), false, List.of(), List.of(), null, null, List.of());
    }
}
