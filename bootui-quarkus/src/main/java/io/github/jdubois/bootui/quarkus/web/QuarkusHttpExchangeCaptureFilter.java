package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * Captures completed HTTP exchanges into the shared {@link HttpExchangeBuffer} — the Quarkus analogue of
 * Spring's Actuator {@code HttpExchangeRepository}, which has no Quarkus counterpart. Registered as a
 * Vert.x route filter (like {@code BootUiQuarkusSafetyFilter}); it is wired only in dev/test launch
 * modes, so production stays dark.
 *
 * <p>It records in {@link HttpServerResponse#bodyEndHandler} so status, headers, byte count and duration
 * are final (a route filter runs before the response is sent, where those are all defaulted). BootUI's
 * own traffic is excluded before recording so the panel never shows its own polling and the self counter
 * mirrors Spring; the exclusion goes through {@link QuarkusBootUiPaths#isBootUiRequest} so it holds under a
 * non-default {@code quarkus.http.root-path} and for a custom {@code bootui.path} mount alike. The buffer
 * caps size and masks downstream, so this filter does minimal, non-blocking work on the event loop.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdProvider} is present (capability-gated), the active server span's
 * trace id is resolved <em>at filter entry</em> — on the event loop, where the span is current — and stamped
 * on the captured exchange so the Live Activity timeline can nest this request's SQL and exceptions under it.
 * The provider is optional: when OpenTelemetry is absent the {@code Instance} is unresolvable and the trace
 * id stays {@code null}, leaving the feed flat.</p>
 *
 * <p>The authenticated principal is resolved <em>at {@code bodyEndHandler} time</em> instead — after
 * routing/business logic has run, so Quarkus's auth mechanism (a core {@code quarkus-vertx-http} concern,
 * independent of whether the {@code quarkus-security} extension is added) has had a chance to authenticate.
 * {@link SecurityIdentity} and {@link QuarkusHttpUser} ship as non-optional transitive dependencies of
 * {@code quarkus-vertx-http}, so this needs no capability gate, unlike the CDI security-event capture in
 * {@code QuarkusSecurityEventCapture}. An unauthenticated or anonymous request stamps {@code null}, matching
 * the Spring adapter's {@code HttpExchange.getPrincipal()} contract.</p>
 *
 * <p>Each request also gets BootUI's own request id at filter entry ({@code docs/PLAN-v2.md} §5.1). Its
 * {@link CorrelationContext} is attached to the request's Vert.x duplicated context, which Quarkus carries to the
 * worker or virtual thread that may continue the request, and made current on the event loop while the rest of the
 * chain runs synchronously. The captured exchange is stamped with that id, whether or not OpenTelemetry is
 * present.</p>
 */
@ApplicationScoped
public class QuarkusHttpExchangeCaptureFilter {

    /** After the safety filter (priority 1000); only ever records, never short-circuits. */
    private static final int PRIORITY = 900;

    private final HttpExchangeBuffer buffer;
    private final TraceIdProvider traceIdProvider;
    private final Config config;
    private final RequestPhases phases;
    private final RuntimeEventSink journal;

    public QuarkusHttpExchangeCaptureFilter(
            HttpExchangeBuffer buffer, Instance<TraceIdProvider> traceIdProvider, Config config) {
        this(buffer, traceIdProvider, config, null, null);
    }

    /**
     * @param phases the phase markers of recent requests, begun here for each request; {@code null} tracks none
     * @param journal the runtime journal, which receives one {@code HTTP} event per request ({@code docs/PLAN-v2.md}
     *     §5.2); {@code null} publishes nothing
     */
    @Inject
    public QuarkusHttpExchangeCaptureFilter(
            HttpExchangeBuffer buffer,
            Instance<TraceIdProvider> traceIdProvider,
            Config config,
            RequestPhases phases,
            RuntimeJournal journal) {
        this.buffer = buffer;
        this.traceIdProvider = traceIdProvider.isResolvable() ? traceIdProvider.get() : null;
        this.config = config;
        this.phases = phases;
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (QuarkusBootUiPaths.isBootUiRequest(config, path)) {
            // BootUI's own request: never recorded, and the work it does, on the event loop or a worker, stays out of
            // the runtime journal (docs/PLAN-v2.md §5.2).
            QuarkusRequestCorrelation.attach(CorrelationContext.BOOTUI);
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
                rc.next();
            }
            return;
        }
        long startNanos = System.nanoTime();
        Instant started = Instant.now();
        String requestId = RequestIds.next();
        CorrelationContext correlation = CorrelationContext.forRequest(requestId);
        QuarkusRequestCorrelation.attach(correlation);
        if (phases != null) {
            phases.begin(requestId);
        }
        HttpServerRequest request = rc.request();
        Map<String, List<String>> requestHeaders = headers(request.headers());
        String traceId = currentTraceId();
        String thread = Thread.currentThread().getName();
        rc.addBodyEndHandler(v -> {
            long durationNanos = System.nanoTime() - startNanos;
            long durationMs = durationNanos / 1_000_000L;
            HttpServerResponse response = rc.response();
            journal.offer(new RuntimeEvent(
                    JournalSource.HTTP,
                    started.toEpochMilli(),
                    durationNanos,
                    requestId,
                    null,
                    traceId,
                    null,
                    thread,
                    null,
                    RequestSlowThreshold.isFailedOrSlow(
                            response.getStatusCode(), durationMs, buffer.slowThresholdMillis()),
                    new HttpPayload(request.method().name(), path, null, null, response.getStatusCode())));
            buffer.record(new CapturedHttpExchange(
                    started,
                    request.method().name(),
                    toUri(request),
                    response.getStatusCode(),
                    durationMs,
                    remoteAddr(rc),
                    principal(rc),
                    null,
                    requestHeaders,
                    headers(response.headers()),
                    traceId,
                    null,
                    requestId));
        });
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            rc.next();
        }
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

    /**
     * The authenticated principal's name, or {@code null} when the request is unauthenticated, the resolved
     * identity is anonymous, or no {@link QuarkusHttpUser} is set on the routing context. Mirrors Spring's
     * {@code HttpExchange.getPrincipal()} contract (null, not the literal string {@code "anonymous"}), so the
     * two adapters render this field identically. Fully guarded so capture never disrupts request handling,
     * mirroring {@link #currentTraceId()}.
     */
    private static String principal(RoutingContext rc) {
        try {
            User user = rc.user();
            if (!(user instanceof QuarkusHttpUser quarkusUser)) {
                return null;
            }
            SecurityIdentity identity = quarkusUser.getSecurityIdentity();
            if (identity == null || identity.isAnonymous()) {
                return null;
            }
            Principal identityPrincipal = identity.getPrincipal();
            return identityPrincipal == null ? null : identityPrincipal.getName();
        } catch (RuntimeException ex) {
            return null;
        }
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
