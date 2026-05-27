package io.github.jdubois.bootui.autoconfigure.otlp;

import java.util.List;
import java.util.Map;

/**
 * Normalized representation of an OTLP span used internally by the BootUI
 * telemetry store and panels.
 *
 * <p>Attribute values are coerced to JSON-friendly types so the controllers
 * can serialize them with the default Jackson configuration shipped by
 * Spring Boot.</p>
 */
public record NormalizedSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        String serviceName,
        String scope,
        long startEpochNanos,
        long endEpochNanos,
        String statusCode,
        String statusMessage,
        Map<String, AttributeValue> attributes,
        List<NormalizedEvent> events) {

    public long durationNanos() {
        return Math.max(0L, endEpochNanos - startEpochNanos);
    }

    public boolean isError() {
        return "ERROR".equals(statusCode);
    }
}
