package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.quarkus.exceptions.QuarkusResourceHandlers;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Captures unhandled HTTP request failures into the shared {@link ExceptionStore} — the Quarkus analogue
 * of Spring's MVC {@code HandlerExceptionResolver}, which has no Quarkus counterpart. Registered as a
 * Vert.x route filter (like {@code QuarkusHttpExchangeCaptureFilter}); it is wired only in dev/test launch
 * modes, so production stays dark. It records in {@link RoutingContext#addBodyEndHandler} so a failure set
 * by a downstream handler is final, and only ever observes — it never short-circuits the response.
 *
 * <p>BootUI's own {@code /bootui} traffic is excluded so the panel never captures its internals, and the
 * request path is taken without its query string to avoid surfacing secrets. The store dedups by
 * cause-chain identity, so a failure also logged by Quarkus (and seen by {@code QuarkusExceptionLogHandler})
 * counts once. In practice {@code QuarkusErrorHandler} logs an unhandled failure synchronously — long before
 * this filter's {@code addBodyEndHandler} callback can run — so {@code QuarkusExceptionLogHandler} is
 * normally the feeder that wins the dedup race; it resolves the same request method/path itself (via the
 * CDI-current {@code RoutingContext}), so this filter's own context is only actually used for the rarer
 * failure that is never logged via {@code java.util.logging} (e.g. a custom handler that swallows logging).
 * Either way the wire is identical, so this filter stays a cheap, harmless safety net.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdProvider} is present (capability-gated), the active server span's
 * trace id is resolved <em>at filter entry</em> — on the event loop, where the span is current — and recorded
 * with the failure so the Live Activity timeline can nest this exception under its owning request. The
 * provider is optional: when OpenTelemetry is absent the {@code Instance} is unresolvable and the trace id
 * stays {@code null}.</p>
 *
 * <p>The handler (JAX-RS resource class + method) is resolved via {@link QuarkusResourceHandlers#currentHandler()}
 * inside the {@code addBodyEndHandler} callback — the same RESTEasy Reactive current-request accessor
 * {@code QuarkusExceptionLogHandler} uses. This is a best-effort read: unlike method/path, which this filter
 * takes directly off its own {@link RoutingContext} and so are always available here, the resource-info
 * accessor depends on the CDI request scope still being active at this later capture point. It resolves
 * correctly whenever that scope is still current (the common case); if a future request-lifecycle change
 * ever tears it down before {@code addBodyEndHandler} fires, this simply degrades to {@code null}, same as
 * every other guarded failure mode on this path. Either way this filter is already a rarely-winning
 * fallback, so a best-effort handler here is a bonus, not a requirement.</p>
 */
@ApplicationScoped
public class QuarkusExceptionCaptureFilter {

    private static final String BASE_PATH = "/bootui";

    /** After the safety filter (priority 1000); only ever records, never short-circuits. */
    private static final int PRIORITY = 900;

    private final ExceptionStore store;
    private final TraceIdProvider traceIdProvider;

    @Inject
    public QuarkusExceptionCaptureFilter(ExceptionStore store, Instance<TraceIdProvider> traceIdProvider) {
        this.store = store;
        this.traceIdProvider = traceIdProvider.isResolvable() ? traceIdProvider.get() : null;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (path != null && (path.equals(BASE_PATH) || path.startsWith(BASE_PATH + "/"))) {
            rc.next();
            return;
        }
        String method = rc.request().method().name();
        String traceId = currentTraceId();
        rc.addBodyEndHandler(v -> {
            Throwable failure = rc.failure();
            if (failure != null) {
                store.record(
                        failure,
                        Thread.currentThread().getName(),
                        method,
                        path,
                        QuarkusResourceHandlers.currentHandler(),
                        "web",
                        traceId);
            }
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
}
