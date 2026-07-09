package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.github.jdubois.bootui.engine.safety.CidrRange;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Local-only access guard for the BootUI Quarkus console — the Vert.x analogue of the Spring adapter's
 * {@code LocalhostOnlyFilter}.
 *
 * <p>This is a <strong>thin binding</strong> over the framework-neutral
 * {@link LocalhostGuard}: it is registered as a global Vert.x HTTP route filter (via the {@link Filters}
 * event) so it runs for <em>every</em> request before routing — including requests that match no JAX-RS
 * resource and static-resource requests — translates the {@link RoutingContext} and the BootUI
 * MicroProfile configuration into a {@link LocalhostGuardRequest} / {@link LocalhostGuardConfig}, asks
 * the guard to {@link LocalhostGuard#decide decide}, and renders the {@link LocalhostGuardDecision}. The
 * access policy itself (trusted-source / Host allow-list / cross-site-write defenses, their evaluation
 * order, and the canonical 403 messages) lives in the engine, shared byte-for-byte with the Spring
 * adapter.</p>
 *
 * <p>A Vert.x route filter is used rather than a JAX-RS {@code @PreMatching ContainerRequestFilter}
 * because a pre-matching filter does not reliably fire for unmatched paths in Quarkus REST (an unmatched
 * {@code POST /bootui/api/overview} is answered 404 by the Vert.x router before the RESTEasy chain runs),
 * which is exactly the cross-site-write case the safety contract must reject.</p>
 *
 * <p>Scope mirrors the Spring adapter's: the whole {@code /bootui} surface (the UI and the API) is
 * source- and Host-gated; cross-site-write protection additionally applies to state-changing methods.
 * The {@code quarkus.http.root-path} prefix is stripped before the scope check (mirroring how the Spring
 * filter strips the servlet context path), so the console is still guarded when the host application runs
 * under a non-default root-path. The three defenses are bypassed entirely only when
 * {@code bootui.allow-non-localhost=true}. Config is read live from MicroProfile {@link Config} and <em>fails closed</em> (a missing/invalid value never
 * widens access). The container-gateway snapshot is resolved once, eagerly at startup and off the Vert.x
 * event loop (the detector does blocking {@code /proc}/DNS work that must never run on the event loop),
 * and only when {@code bootui.trust-container-gateway} is not {@code OFF} so a default deployment never
 * touches {@code /proc} or DNS.</p>
 */
@ApplicationScoped
public class BootUiQuarkusSafetyFilter {

    private static final Logger LOG = Logger.getLogger(BootUiQuarkusSafetyFilter.class);

    private static final String BASE_PATH = "/bootui";
    private static final String API_PATH = "/bootui/api";

    static final String ALLOW_NON_LOCALHOST_KEY = "bootui.allow-non-localhost";
    static final String ALLOWED_HOSTS_KEY = "bootui.allowed-hosts";
    static final String TRUSTED_PROXIES_KEY = "bootui.trusted-proxies";
    static final String TRUST_CONTAINER_GATEWAY_KEY = "bootui.trust-container-gateway";
    static final String ROOT_PATH_KEY = "quarkus.http.root-path";

    /**
     * Run early, before resource/static route handlers. Higher priority filters run first; we only ever
     * abort (403) or call {@link RoutingContext#next()}, so this never short-circuits a legit request.
     */
    private static final int PRIORITY = 1000;

    private final Config config;
    private final LocalhostGuard guard = new LocalhostGuard();
    private final ContainerGatewayDetector gatewayDetector;

    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    @Inject
    public BootUiQuarkusSafetyFilter(Config config) {
        this(config, new ContainerGatewayDetector());
    }

    BootUiQuarkusSafetyFilter(Config config, ContainerGatewayDetector gatewayDetector) {
        this.config = config;
        this.gatewayDetector = gatewayDetector;
    }

    /**
     * Registers the route filter. This observer is notified during HTTP server setup at startup (on the
     * main thread, off the Vert.x event loop), so it is also where the blocking container-gateway
     * snapshot is resolved.
     */
    public void register(@Observes Filters filters) {
        resolveGatewaySnapshot();
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String relativePath = bootUiRelativePath(rc.normalizedPath());
        if (!isBootUiRequest(relativePath)) {
            rc.next();
            return;
        }

        // Apply security headers to all BootUI responses (including 403 rejections below).
        applySecurityHeaders(rc.response(), relativePath);

        LocalhostGuardDecision decision = guard.decide(toGuardRequest(rc), buildConfig());

        if (decision instanceof Reject reject) {
            logRejection(reject, rc);
            reject(rc, reject.message());
            return;
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            warnTrustedGatewayOnce(allow.trustedGateway());
        }
        rc.next();
    }

    /**
     * Applies the BootUI security-header policy to the response. Called before the guard check so the
     * headers are present on both 403 rejections and passing responses, at parity with the Spring adapters'
     * {@code SecurityHeadersFilter}.
     */
    private void applySecurityHeaders(HttpServerResponse response, String relativePath) {
        response.putHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, BootUiSecurityHeaders.CSP_VALUE);
        response.putHeader(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS, BootUiSecurityHeaders.NOSNIFF);
        response.putHeader(BootUiSecurityHeaders.X_FRAME_OPTIONS, BootUiSecurityHeaders.DENY);
        response.putHeader(
                BootUiSecurityHeaders.REFERRER_POLICY, BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);

        String cacheControl = BootUiSecurityHeaders.cacheControl(relativePath, API_PATH);
        response.putHeader(BootUiSecurityHeaders.CACHE_CONTROL, cacheControl);
        if (BootUiSecurityHeaders.shouldSetPragma(cacheControl)) {
            response.putHeader(BootUiSecurityHeaders.PRAGMA, BootUiSecurityHeaders.PRAGMA_NO_CACHE);
        }
    }

    /**
     * Returns {@code true} for the BootUI UI and API surface, using the same strict boundary check as the
     * Spring adapter (an exact match or a {@code /}-delimited sub-path) so an unrelated path such as
     * {@code /bootui-other} is not caught.
     */
    static boolean isBootUiRequest(String path) {
        if (path == null) {
            return false;
        }
        return path.equals(BASE_PATH)
                || path.startsWith(BASE_PATH + "/")
                || path.equals(API_PATH)
                || path.startsWith(API_PATH + "/");
    }

    /**
     * Removes the configured {@code quarkus.http.root-path} prefix from the request path so the BootUI
     * scope check is root-path-relative. Quarkus mounts the whole application — including BootUI's JAX-RS
     * resources and its static UI — under {@code quarkus.http.root-path}, so under a non-default root-path
     * (e.g. {@code /app}) the console is served at {@code /app/bootui/**} while this global Vert.x filter
     * still sees the full path. Without stripping, {@link #isBootUiRequest} would not recognize the
     * prefixed path and the guard would be skipped (fail-open). The root-path is read live and
     * <em>fails closed</em>: a missing/blank value normalizes to {@code ""} (no prefix), which still guards
     * the default {@code /bootui} surface. Delegates to {@link QuarkusRootPath}, shared with
     * {@link QuarkusPanelAccessFilter} so the two filters can never drift in root-path handling.
     */
    String bootUiRelativePath(String normalizedPath) {
        return QuarkusRootPath.stripPrefix(normalizedPath, QuarkusRootPath.normalize(rootPath()));
    }

    private String rootPath() {
        return config.getOptionalValue(ROOT_PATH_KEY, String.class).orElse("/");
    }

    /** Normalizes a {@code quarkus.http.root-path} value to a strip-prefix ({@code ""} for the default). */
    static String normalizeRootPath(String raw) {
        return QuarkusRootPath.normalize(raw);
    }

    /**
     * Builds the neutral guard request from the Vert.x request, passing <em>raw</em> values: the guard
     * owns all parsing. The source address is always the real TCP peer ({@link
     * HttpServerRequest#remoteAddress()}), never a forwarded header.
     */
    LocalhostGuardRequest toGuardRequest(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        return new LocalhostGuardRequest(
                request.method().name(),
                remoteAddr(request.remoteAddress()),
                hostAuthority(request.getHeader("Host"), request.authority()),
                request.getHeader("Origin"),
                request.getHeader("Sec-Fetch-Site"));
    }

    /** The raw socket peer address (e.g. {@code 127.0.0.1}), or {@code null} when unavailable. */
    static String remoteAddr(SocketAddress remoteAddress) {
        return remoteAddress == null ? null : remoteAddress.hostAddress();
    }

    /**
     * Sources the host authority for the Host allow-list. The raw {@code Host} header is preferred so the
     * parse is byte-identical to the Spring adapter for the common HTTP/1.1 case; when no {@code Host}
     * header is present (HTTP/2, which carries it in the {@code :authority} pseudo-header) the Vert.x
     * {@link HttpServerRequest#authority()} is rendered back to {@code host[:port]} (bracketing IPv6) so
     * the allow-list still applies. Returns {@code null} when neither is present, which the guard treats
     * as a missing Host (allowed for non-browser local clients).
     */
    static String hostAuthority(String hostHeader, HostAndPort authority) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader;
        }
        if (authority != null && authority.host() != null && !authority.host().isBlank()) {
            return renderAuthority(authority);
        }
        return hostHeader;
    }

    private static String renderAuthority(HostAndPort authority) {
        String host = authority.host();
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        String renderedHost = ipv6 ? "[" + host + "]" : host;
        int port = authority.port();
        return port >= 0 ? renderedHost + ":" + port : renderedHost;
    }

    /**
     * Builds the per-request guard configuration from live MicroProfile config plus the once-resolved
     * gateway snapshot. When {@code bootui.trust-container-gateway} is {@code OFF} (the default) the
     * snapshot is empty and the guard never trusts a gateway.
     */
    private LocalhostGuardConfig buildConfig() {
        GatewayTrust gatewayTrust = gatewayTrust();
        boolean container;
        Set<InetAddress> gateways;
        if (gatewayTrust == GatewayTrust.OFF) {
            container = false;
            gateways = Set.of();
        } else {
            container = this.inContainer;
            gateways = this.containerGateways;
        }
        return new LocalhostGuardConfig(
                allowNonLocalhost(), allowedHosts(), trustedRanges(), gatewayTrust, container, gateways);
    }

    private boolean allowNonLocalhost() {
        return config.getOptionalValue(ALLOW_NON_LOCALHOST_KEY, Boolean.class).orElse(Boolean.FALSE);
    }

    private List<String> allowedHosts() {
        return config.getOptionalValues(ALLOWED_HOSTS_KEY, String.class).orElse(List.of());
    }

    private List<CidrRange> trustedRanges() {
        List<String> configured =
                config.getOptionalValues(TRUSTED_PROXIES_KEY, String.class).orElse(List.of());
        List<CidrRange> parsed = new ArrayList<>();
        for (String entry : configured) {
            CidrRange range = CidrRange.parse(entry);
            if (range != null) {
                parsed.add(range);
            } else if (entry != null && !entry.isBlank()) {
                LOG.warnf("BootUI ignoring malformed %s entry '%s'", TRUSTED_PROXIES_KEY, entry);
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * Reads {@code bootui.trust-container-gateway} and maps it onto the neutral {@link GatewayTrust}. A
     * missing, blank, or invalid value fails closed to {@link GatewayTrust#OFF}.
     */
    private GatewayTrust gatewayTrust() {
        String raw = config.getOptionalValue(TRUST_CONTAINER_GATEWAY_KEY, String.class)
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return GatewayTrust.OFF;
        }
        try {
            return GatewayTrust.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warnf(
                    "Ignoring invalid BootUI property '%s=%s'; falling back to OFF.", TRUST_CONTAINER_GATEWAY_KEY, raw);
            return GatewayTrust.OFF;
        }
    }

    /**
     * Resolves and caches the container detection result and trusted gateways exactly once, eagerly at
     * startup. Skipped entirely (no {@code /proc}/DNS access) when gateway trust is {@code OFF}. The
     * trusted set is the union of the route-table default gateway and any Docker Desktop gateway. The
     * detector already fails soft; this additionally guards against any unexpected error, leaving the
     * snapshot empty (so gateway trust is never granted) on failure.
     */
    void resolveGatewaySnapshot() {
        if (gatewayTrust() == GatewayTrust.OFF) {
            return;
        }
        try {
            this.inContainer = gatewayDetector.isInContainer();
            Set<InetAddress> gateways = new LinkedHashSet<>();
            gatewayDetector.defaultGateway().ifPresent(gateways::add);
            gateways.addAll(gatewayDetector.dockerDesktopGateways());
            this.containerGateways = Set.copyOf(gateways);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "BootUI container-gateway detection failed; not trusting any gateway.");
            this.inContainer = false;
            this.containerGateways = Set.of();
        }
    }

    private void warnTrustedGatewayOnce(InetAddress gateway) {
        if (loggedTrustedGateway) {
            return;
        }
        loggedTrustedGateway = true;
        LOG.warnf(
                "BootUI trusting auto-detected container gateway %s (/32) for loopback-equivalent access.",
                gateway != null ? gateway.getHostAddress() : null);
    }

    private void logRejection(Reject reject, RoutingContext rc) {
        HttpServerRequest request = rc.request();
        String path = rc.normalizedPath();
        switch (reject.reason()) {
            case NON_LOOPBACK_SOURCE ->
                LOG.warnf(
                        "BootUI rejected non-loopback request from %s to %s",
                        remoteAddr(request.remoteAddress()), path);
            case DISALLOWED_HOST ->
                LOG.warnf("BootUI rejected request with disallowed Host '%s' to %s", request.getHeader("Host"), path);
            case CROSS_SITE_WRITE ->
                LOG.warnf(
                        "BootUI rejected cross-site %s request to %s",
                        request.method().name(), path);
        }
    }

    private void reject(RoutingContext rc, String message) {
        HttpServerResponse response = rc.response();
        response.setStatusCode(403)
                .putHeader("Content-Type", "application/json")
                .end("{\"error\":\"" + message + "\"}");
    }
}
