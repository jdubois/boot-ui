package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * Request from the browser to probe a local HTTP endpoint.
 */
public record HttpProbeRequest(String method, String path, String body, Map<String, String> headers) {}
