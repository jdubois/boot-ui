package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusThreadKinds;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Context;
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
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

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
 * <p>Capture never disturbs the request: every callback is guarded, and a capture failure is logged under
 * BootUI's own logger (which the Exceptions panel ignores) instead of propagating into Vert.x, where
 * {@code QuarkusErrorHandler} would report it as the application's failure. Response headers are read from a
 * copy taken in {@code headersEndHandler} whenever the response is ended off the event loop, because the live
 * map may then still be mutated by the event loop while the body-end handler runs.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdSource} is present (capability-gated), the active server span's
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

    private static final Logger LOG = Logger.getLogger(QuarkusHttpExchangeCaptureFilter.class);

    private final HttpExchangeBuffer buffer;
    private final TraceIdSource traceIdProvider;
    private final Config config;
    private final RequestPhases phases;
    private final RuntimeEventSink journal;

    public QuarkusHttpExchangeCaptureFilter(
            HttpExchangeBuffer buffer, Instance<TraceIdSource> traceIdProvider, Config config) {
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
            Instance<TraceIdSource> traceIdProvider,
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
        // Routed on a Vert.x event loop: the agent's blocking sensor watches it (M5-5c).
        QuarkusThreadKinds.registerIfEventLoop();
        CorrelationContext correlation = null;
        try {
            correlation = correlate(rc);
        } catch (RuntimeException failure) {
            logCaptureFailure(failure);
        }
        if (correlation == null) {
            rc.next();
            return;
        }
        // The request's measurement starts inside its correlation scope, not before it: a scope hands back the request
        // its thread was metered for when it opened, so a meter begun outside would follow this event loop on to the
        // next request it serves once routing returns with the response still pending.
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            if (correlation == CorrelationContext.BOOTUI) {
                rc.next();
                return;
            }
            try {
                observe(rc, correlation);
            } catch (RuntimeException failure) {
                logCaptureFailure(failure);
            }
            // The BootUI agent's code-paths fragment of this request on the event loop (docs/PLAN-v2.md M5-4a): a
            // blocking resource method runs on a worker, where its own fragment starts at its outermost bean call.
            AgentCodePaths.begin();
            AgentCodePaths.phase(RequestPhase.FILTERS);
            try {
                rc.next();
            } finally {
                AgentCodePaths.end();
            }
        }
    }

    /**
     * Correlates the request on its Vert.x duplicated context and returns the correlation to run the rest of its
     * routing under, without yet measuring anything.
     */
    private CorrelationContext correlate(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (QuarkusBootUiPaths.isBootUiRequest(config, path)) {
            // BootUI's own request: never recorded, and the work it does, on the event loop or a worker, stays out of
            // the runtime journal (docs/PLAN-v2.md §5.2).
            QuarkusRequestCorrelation.attach(CorrelationContext.BOOTUI);
            return CorrelationContext.BOOTUI;
        }
        CorrelationContext correlation = CorrelationContext.forRequest(RequestIds.next());
        QuarkusRequestCorrelation.attach(correlation);
        return correlation;
    }

    /** Starts observing the correlated request, from inside its correlation scope. */
    private void observe(RoutingContext rc, CorrelationContext correlation) {
        String path = rc.normalizedPath();
        String requestId = correlation.requestId();
        long startNanos = System.nanoTime();
        Instant started = Instant.now();
        if (phases != null) {
            phases.begin(requestId);
        }
        if (journal.records(JournalSource.RESOURCES)) {
            SegmentMeter.shared().begin(requestId);
        }
        HttpServerRequest request = rc.request();
        Map<String, List<String>> requestHeaders = headers(request.headers());
        String traceId = currentTraceId();
        String thread = Thread.currentThread().getName();
        AtomicReference<Map<String, List<String>>> committedResponseHeaders = new AtomicReference<>();
        rc.addHeadersEndHandler(v -> {
            try {
                committedResponseHeaders.set(headers(rc.response().headers()));
            } catch (RuntimeException failure) {
                logCaptureFailure(failure);
            }
        });
        rc.addBodyEndHandler(v -> {
            // Runs on the thread that ended the response: on a worker, no end() closes the request's scope, so the
            // code-paths phase its response filter marked is cleared here, before the worker takes other work.
            AgentCodePaths.clearPhase();
            try {
                long durationNanos = System.nanoTime() - startNanos;
                long durationMs = durationNanos / 1_000_000L;
                HttpServerResponse response = rc.response();
                // Ends the request's measurement (docs/PLAN-v2.md §5.11), closing the segment its worker left open.
                ResourceUsage resources = SegmentMeter.shared().take(requestId);
                if (phases != null) {
                    phases.end(requestId);
                }
                try {
                    journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            started.toEpochMilli(),
                            durationNanos,
                            correlation,
                            traceId,
                            thread,
                            null,
                            RequestSlowThreshold.isFailedOrSlow(
                                    response.getStatusCode(), durationMs, buffer.slowThresholdMillis()),
                            new HttpPayload(
                                    request.method().name(),
                                    path,
                                    null,
                                    null,
                                    response.getStatusCode(),
                                    resources,
                                    RequestTiming.of(startNanos, phases == null ? null : phases.markers(requestId)))));
                } catch (RuntimeException ex) {
                    // Publishing never disturbs the request it observes.
                }
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
                        responseHeaders(response, committedResponseHeaders.get()),
                        traceId,
                        null,
                        requestId));
            } catch (RuntimeException failure) {
                logCaptureFailure(failure);
            }
        });
    }

    /**
     * The response headers as sent. Vert.x hands the live header map to the channel and only then runs the
     * body-end handler. On the event loop that write is synchronous, so the live map is final and also carries
     * the {@code Set-Cookie} headers Vert.x appends after {@code headersEndHandler}. On any other thread (a
     * worker or virtual thread ending the response) the event loop may still be mutating that map, so reading
     * it can throw; the copy taken in {@code headersEndHandler}, just before the hand-off, is used instead. Vert.x
     * Web runs headers-end handlers in reverse registration order, so this early filter's copy also sees headers
     * added by handlers registered after it.
     */
    private static Map<String, List<String>> responseHeaders(
            HttpServerResponse response, Map<String, List<String>> committed) {
        Map<String, List<String>> fallback = committed == null ? Map.of() : committed;
        if (!Context.isOnEventLoopThread()) {
            return fallback;
        }
        try {
            return headers(response.headers());
        } catch (RuntimeException failure) {
            return fallback;
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

    private static void logCaptureFailure(RuntimeException failure) {
        LOG.warnf(
                "BootUI skipped a Quarkus HTTP exchange capture (%s)",
                failure.getClass().getSimpleName());
    }

    private static Map<String, List<String>> headers(io.vertx.core.MultiMap headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers) {
            result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>(1)).add(entry.getValue());
        }
        return result;
    }
}
