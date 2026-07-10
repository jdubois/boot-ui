package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * Result of an HTTP probe.
 *
 * <p>The {@code truncated} flag is {@code true} when the response body exceeded the probe's
 * configured byte budget and was cut at that limit. The body is still included (up to the limit) so
 * the browser can render partial content; callers should surface a clear truncation notice to the
 * user when this flag is set.
 */
public record HttpProbeResponse(
        int status,
        String statusText,
        Map<String, String> headers,
        String body,
        long durationMs,
        String error,
        boolean truncated) {}
