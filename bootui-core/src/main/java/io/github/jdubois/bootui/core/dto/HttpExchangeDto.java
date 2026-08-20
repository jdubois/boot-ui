package io.github.jdubois.bootui.core.dto;

import java.time.Instant;
import java.util.List;

/**
 * One captured inbound HTTP exchange.
 *
 * @param correlationIds correlation identifiers read from this request's inbound headers (for example
 *     {@code X-Correlation-ID}), already masked or withheld by the live value-exposure policy and each
 *     carrying an opaque lookup identity for exact filtering; empty when the request carried none
 */
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
        List<HttpHeaderDto> responseHeaders,
        List<CorrelationIdDto> correlationIds) {

    public HttpExchangeDto {
        correlationIds = correlationIds == null ? List.of() : List.copyOf(correlationIds);
    }
}
