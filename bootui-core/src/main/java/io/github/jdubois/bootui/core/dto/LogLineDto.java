package io.github.jdubois.bootui.core.dto;

/**
 * A single log line for the live log tail.
 *
 * <p>{@code traceId} and {@code spanId} are populated from the logging MDC when distributed
 * tracing (for example Micrometer Tracing) is active, enabling correlation with the Traces and
 * HTTP Exchanges panels. They are {@code null} when no trace context is present.
 */
public record LogLineDto(
        long timestamp, String level, String logger, String message, String thread, String traceId, String spanId) {}
