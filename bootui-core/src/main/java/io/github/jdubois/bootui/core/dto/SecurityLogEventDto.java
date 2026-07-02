package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One security/audit event formatted for the Security Logs panel.
 *
 * @param traceId the distributed-trace id active when the event was captured, or {@code null} when
 *     unknown (Spring's audit repository carries no trace id) or no trace context was active. Used by
 *     the Quarkus adapter's Live Activity panel to correlate this event to the request that produced it.
 */
public record SecurityLogEventDto(
        String timestamp, String principal, String type, List<SecurityLogDataDto> data, String traceId) {}
