package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One security/audit event formatted for the Security Logs panel.
 *
 * @param traceId the distributed-trace id active when the event was captured, or {@code null} when
 *     unknown or no trace context was active. Populated on Quarkus and Spring WebFlux (both stamp it from
 *     a {@code TraceIdProvider} at the security-event capture point) so Live Activity can correlate this
 *     event to the request that produced it; {@code null} on Spring servlet (MVC), since Spring Boot's
 *     audit repository carries no trace id there — that adapter correlates by a serving-thread classifier
 *     instead (see {@code ActivityEntryDto.parentId}).
 */
public record SecurityLogEventDto(
        String timestamp, String principal, String type, List<SecurityLogDataDto> data, String traceId) {}
