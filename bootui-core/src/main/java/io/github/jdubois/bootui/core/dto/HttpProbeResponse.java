package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * Result of an HTTP probe.
 */
public record HttpProbeResponse(
        int status, String statusText, Map<String, String> headers, String body, long durationMs, String error) {}
