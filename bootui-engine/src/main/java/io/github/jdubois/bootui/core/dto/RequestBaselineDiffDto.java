package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Intra-route comparison between a slow or failed HTTP request (or a request compared explicitly via
 * {@code ?compareWith=}) and a retained, complete baseline request on the same route.
 *
 * @param baselineRequestId the compared baseline request's id
 * @param baselineDurationMicros how long the baseline request took, in microseconds
 * @param routeP50Micros the route's median duration in microseconds from the run's aggregates, or {@code null} when
 *     unavailable
 * @param durationDeltaMicros target duration minus baseline duration, in microseconds
 * @param sqlExecutionsDelta target verified JDBC executions minus baseline verified JDBC executions, or {@code null}
 *     when SQL execution is unavailable or unverified
 * @param sqlDurationMicrosDelta target verified SQL duration minus baseline verified SQL duration in microseconds, or
 *     {@code null} when SQL execution is unavailable or unverified
 * @param connectionWaitMicrosDelta target connection-acquisition wait minus baseline connection-acquisition wait in
 *     microseconds, or {@code null} when connection capture is unavailable or neither request recorded a connection hold
 * @param cacheHitsDelta target cache hits minus baseline cache hits, or {@code null} when the cache source is
 *     unavailable
 * @param cacheMissesDelta target cache misses minus baseline cache misses, or {@code null} when the cache source is
 *     unavailable
 * @param restClientCallsDelta target outbound REST client calls minus baseline calls, or {@code null} when the REST
 *     client source is unavailable
 * @param restClientDurationMicrosDelta target outbound REST client duration minus baseline duration in microseconds,
 *     or {@code null} when the REST client source is unavailable
 * @param ormFlushesDelta target Hibernate session flushes minus baseline flushes, or {@code null} when ORM sessions
 *     were not recorded
 * @param ormAutoFlushesDelta target Hibernate auto-flushes before a query minus baseline auto-flushes, or {@code null}
 *     when ORM sessions were not recorded
 * @param ormMaxEntitiesDelta target peak entities in persistence context minus baseline peak entities, or {@code null}
 *     when ORM entity counts were not measured
 * @param gcPausesTarget JVM-wide GC pauses that completed during the target request, or {@code null} when the
 *     {@code resources} source is not recorded
 * @param gcPausesBaseline JVM-wide GC pauses that completed during the baseline request, or {@code null} when the
 *     {@code resources} source is not recorded
 * @param distinctSqlShapesOnlyInTarget up to 5 normalized, literal-free SQL shapes ({@code JournalTextExposure.masked()})
 *     that ran in the target request but not in the baseline request, or whose execution count increased most
 * @param candidates retained, complete baseline candidate requests on the same route for interactive comparison
 */
public record RequestBaselineDiffDto(
        String baselineRequestId,
        long baselineDurationMicros,
        Long routeP50Micros,
        long durationDeltaMicros,
        Integer sqlExecutionsDelta,
        Long sqlDurationMicrosDelta,
        Long connectionWaitMicrosDelta,
        Integer cacheHitsDelta,
        Integer cacheMissesDelta,
        Integer restClientCallsDelta,
        Long restClientDurationMicrosDelta,
        Integer ormFlushesDelta,
        Integer ormAutoFlushesDelta,
        Integer ormMaxEntitiesDelta,
        Long gcPausesTarget,
        Long gcPausesBaseline,
        List<String> distinctSqlShapesOnlyInTarget,
        List<RequestBaselineCandidateDto> candidates) {

    public RequestBaselineDiffDto {
        distinctSqlShapesOnlyInTarget = DtoCollections.immutableCopy(distinctSqlShapesOnlyInTarget);
        candidates = DtoCollections.immutableCopy(candidates);
    }
}
