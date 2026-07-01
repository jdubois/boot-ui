package io.github.jdubois.bootui.engine.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral capture of a single completed HTTP exchange, before masking or DTO assembly.
 *
 * <p>Adapters translate their native request/response (Spring's {@code HttpExchange}, a Vert.x
 * {@code RoutingContext}) into this record and feed it to {@link HttpExchangesService}; the engine
 * owns masking, trace-id extraction, self-exclusion and paging so the wire is identical across
 * frameworks. Header maps are raw, multi-valued and case-preserving — the service sorts and folds them.
 *
 * <p>{@code traceId} is the trace id the adapter resolved from the active server span (the Quarkus
 * adapter reads {@code Span.current()} when OpenTelemetry is present); it is preferred over the trace id
 * the service would otherwise parse from inbound propagation headers, so signal-to-request correlation
 * works for same-origin local requests that carry no {@code traceparent}. Pass {@code null} to keep the
 * header-derived behavior (the Spring adapter does).
 */
public record CapturedHttpExchange(
        Instant timestamp,
        String method,
        java.net.URI uri,
        int status,
        Long durationMs,
        String remoteAddress,
        String principal,
        String sessionId,
        Map<String, List<String>> requestHeaders,
        Map<String, List<String>> responseHeaders,
        String traceId) {

    public CapturedHttpExchange {
        requestHeaders = requestHeaders == null ? Map.of() : requestHeaders;
        responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
    }
}
