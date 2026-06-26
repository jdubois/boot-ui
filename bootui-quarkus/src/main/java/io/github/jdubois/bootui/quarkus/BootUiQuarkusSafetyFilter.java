package io.github.jdubois.bootui.quarkus;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal cross-site-write safety floor for the BootUI Quarkus console.
 *
 * <p>This is registered as a global Vert.x HTTP route filter (via the {@link Filters} event) rather
 * than a JAX-RS {@code @PreMatching ContainerRequestFilter}: a Vert.x filter runs for <em>every</em>
 * HTTP request before routing, including requests that match no JAX-RS resource and static-resource
 * requests. A JAX-RS pre-matching filter does not reliably fire for unmatched paths in Quarkus REST
 * (an unmatched {@code POST /bootui/api/overview} is answered 404 by the Vert.x router before the
 * RESTEasy chain runs), which is exactly the cross-site-write case the safety contract must reject.
 *
 * <p>Scope is deliberately reduced for the tracer bullet: it only rejects cross-site state-changing
 * requests under {@code /bootui/api/}. Safe methods (GET/HEAD/OPTIONS) pass. The full shared
 * {@code LocalhostGuard} port (loopback-source trust + Host allow-list + fail-closed on missing
 * headers) lands later; see the {@code TODO(R7)} notes below.
 */
@ApplicationScoped
public class BootUiQuarkusSafetyFilter {

    private static final String GUARDED_API_PREFIX = "/bootui/api/";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Run early, before resource/static route handlers. Higher priority filters run first; we only
     * ever abort (403) or call {@link RoutingContext#next()}, so this never short-circuits a legit
     * request.
     */
    private static final int PRIORITY = 1000;

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (path == null || !path.startsWith(GUARDED_API_PREFIX)) {
            rc.next();
            return;
        }
        String method = rc.request().method().name();
        if (SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            // TODO(R7): passive reads pass this floor; the shared LocalhostGuard additionally gates
            // GETs by loopback-source trust and Host allow-list.
            rc.next();
            return;
        }
        if (isCrossSiteWrite(rc)) {
            rc.response()
                    .setStatusCode(403)
                    .putHeader("Content-Type", "text/plain; charset=utf-8")
                    .end("BootUI rejected a cross-site state-changing request.");
            return;
        }
        // TODO(R7): a write carrying NEITHER Origin NOR Sec-Fetch-Site still passes; the full
        // LocalhostGuard fails closed by also requiring a trusted loopback source.
        rc.next();
    }

    private boolean isCrossSiteWrite(RoutingContext rc) {
        String secFetchSite = rc.request().getHeader("Sec-Fetch-Site");
        if (secFetchSite != null
                && ("cross-site".equalsIgnoreCase(secFetchSite) || "cross-origin".equalsIgnoreCase(secFetchSite))) {
            return true;
        }
        String origin = rc.request().getHeader("Origin");
        if (origin != null) {
            // A present Origin that is opaque ("null"/blank) or unparseable yields a null host and
            // fails closed. Host comparison is host-only so the Vite dev proxy (:5173 -> :8080) passes.
            String originHost = hostOf(origin);
            String requestHost = hostPart(rc.request().getHeader("Host"));
            return originHost == null || requestHost == null || !originHost.equalsIgnoreCase(requestHost);
        }
        return false;
    }

    private static String hostOf(String origin) {
        String trimmed = origin.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null; // opaque origin -> fail closed
        }
        try {
            return stripBrackets(URI.create(trimmed).getHost());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String hostPart(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String value = hostHeader.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
