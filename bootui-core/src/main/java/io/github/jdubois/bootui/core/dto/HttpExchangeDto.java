package io.github.jdubois.bootui.core.dto;

import java.time.Instant;
import java.util.List;

/**
 * One recorded inbound HTTP exchange.
 *
 * <p>{@code route} and {@code routeSource} carry the low-cardinality route the exchange is grouped under in
 * the route summary: the framework template ({@code FRAMEWORK_TEMPLATE}), the single best declared mapping
 * ({@code DECLARED_MAPPING}), or the path with every value-like segment replaced ({@code MASKED_PATH}). Both
 * are {@code null} from a server that predates route summaries.</p>
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
        String route,
        String routeSource) {

    public HttpExchangeDto {
        requestHeaders = DtoCollections.immutableCopy(requestHeaders);
        responseHeaders = DtoCollections.immutableCopy(responseHeaders);
    }

    /** An exchange without route resolution, for callers that have no route evidence to add. */
    public HttpExchangeDto(
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
            List<HttpHeaderDto> responseHeaders) {
        this(
                id,
                timestamp,
                method,
                path,
                query,
                uri,
                status,
                statusFamily,
                durationMs,
                responseSizeBytes,
                remoteAddress,
                principal,
                sessionId,
                traceId,
                requestHeaders,
                responseHeaders,
                null,
                null);
    }
}
