package io.github.jdubois.bootui.core.dto;

/**
 * At-a-glance key performance indicators for the Live Activity dashboard.
 *
 * <p>All values are derived from the same bounded in-memory buffers that feed the activity stream,
 * computed over the entries currently retained. Fields are nullable when their backing source is
 * unavailable so the UI can degrade gracefully.</p>
 *
 * @param requestsPerMinute recent request throughput estimate
 * @param errorRatePercent percentage of recent requests that returned a 4xx/5xx status
 * @param p50LatencyMs median request latency in milliseconds, or {@code null}
 * @param p95LatencyMs 95th-percentile request latency in milliseconds, or {@code null}
 * @param slowestEndpoint path of the slowest recent request, or {@code null}
 * @param slowestEndpointMs latency of the slowest recent request in milliseconds, or {@code null}
 * @param activeExceptionCount number of distinct exception groups currently retained
 * @param sqlPerMinute recent SQL execution throughput estimate
 * @param slowestQueryMs slowest retained SQL execution in milliseconds, or {@code null}
 * @param healthStatus current Actuator health status, or {@code null}
 * @param heapUsedBytes current JVM heap usage in bytes, or {@code null}
 * @param heapMaxBytes maximum JVM heap size in bytes, or {@code null}
 */
public record ActivityKpiDto(
        double requestsPerMinute,
        double errorRatePercent,
        Long p50LatencyMs,
        Long p95LatencyMs,
        String slowestEndpoint,
        Long slowestEndpointMs,
        int activeExceptionCount,
        double sqlPerMinute,
        Long slowestQueryMs,
        String healthStatus,
        Long heapUsedBytes,
        Long heapMaxBytes) {}
