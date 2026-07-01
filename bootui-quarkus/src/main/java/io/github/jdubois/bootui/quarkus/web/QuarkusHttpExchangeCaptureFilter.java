package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures completed HTTP exchanges into the shared {@link HttpExchangeBuffer} — the Quarkus analogue of
 * Spring's Actuator {@code HttpExchangeRepository}, which has no Quarkus counterpart. Registered as a
 * Vert.x route filter (like {@code BootUiQuarkusSafetyFilter}); it is wired only in dev/test launch
 * modes, so production stays dark.
 *
 * <p>It records in {@link HttpServerResponse#bodyEndHandler} so status, headers, byte count and duration
 * are final (a route filter runs before the response is sent, where those are all defaulted). BootUI's
 * own {@code /bootui} traffic is excluded before recording so the panel never shows its own polling and
 * the self counter mirrors Spring. The buffer caps size and masks downstream, so this filter does
 * minimal, non-blocking work on the event loop.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdProvider} is present (capability-gated), the active server span's
 * trace id is resolved <em>at filter entry</em> — on the event loop, where the span is current — and stamped
 * on the captured exchange so the Live Activity timeline can nest this request's SQL and exceptions under it.
 * The provider is optional: when OpenTelemetry is absent the {@code Instance} is unresolvable and the trace
 * id stays {@code null}, leaving the feed flat.</p>
 */
@ApplicationScoped
public class QuarkusHttpExchangeCaptureFilter {

    private static final String BASE_PATH = "/bootui";

    /** After the safety filter (priority 1000); only ever records, never short-circuits. */
    private static final int PRIORITY = 900;

    private final HttpExchangeBuffer buffer;
    private final TraceIdProvider traceIdProvider;

    @Inject
    public QuarkusHttpExchangeCaptureFilter(HttpExchangeBuffer buffer, Instance<TraceIdProvider> traceIdProvider) {
        this.buffer = buffer;
        this.traceIdProvider = traceIdProvider.isResolvable() ? traceIdProvider.get() : null;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (isBootUiRequest(path)) {
            rc.next();
            return;
        }
        long startNanos = System.nanoTime();
        Instant started = Instant.now();
        HttpServerRequest request = rc.request();
        Map<String, List<String>> requestHeaders = headers(request.headers());
        String traceId = currentTraceId();
        rc.addBodyEndHandler(v -> {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            HttpServerResponse response = rc.response();
            buffer.record(new CapturedHttpExchange(
                    started,
                    request.method().name(),
                    toUri(request),
                    response.getStatusCode(),
                    durationMs,
                    remoteAddr(rc),
                    null,
                    null,
                    requestHeaders,
                    headers(response.headers()),
                    traceId));
        });
        rc.next();
    }

    /**
     * The active span's trace id, or {@code null} when OpenTelemetry is absent (no provider) or no span is in
     * context. Fully guarded so capture never disrupts request handling.
     */
    private String currentTraceId() {
        if (traceIdProvider == null) {
            return null;
        }
        try {
            return traceIdProvider.currentTraceId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static boolean isBootUiRequest(String path) {
        return path != null && (path.equals(BASE_PATH) || path.startsWith(BASE_PATH + "/"));
    }

    private static URI toUri(HttpServerRequest request) {
        try {
            return new URI(request.absoluteURI());
        } catch (URISyntaxException | RuntimeException ex) {
            try {
                return new URI(request.uri());
            } catch (URISyntaxException ignored) {
                return null;
            }
        }
    }

    private static String remoteAddr(RoutingContext rc) {
        return rc.request().remoteAddress() == null
                ? null
                : rc.request().remoteAddress().hostAddress();
    }

    private static Map<String, List<String>> headers(io.vertx.core.MultiMap headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers) {
            result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>(1)).add(entry.getValue());
        }
        return result;
    }
}
