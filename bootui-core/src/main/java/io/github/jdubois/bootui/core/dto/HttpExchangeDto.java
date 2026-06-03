package io.github.jdubois.bootui.core.dto;

import java.time.Instant;
import java.util.List;

public record HttpExchangeDto(
        String id,
        Instant timestamp,
        String method,
        String path,
        String query,
        String uri,
        int status,
        String statusFamily,
        Long durationMs,
        Long responseSizeBytes,
        String remoteAddress,
        String principal,
        String sessionId,
        String traceId,
        List<HttpHeaderDto> requestHeaders,
        List<HttpHeaderDto> responseHeaders) {}
