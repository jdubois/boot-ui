package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.CidrRange;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects any BootUI request that does not originate from the local machine.
 *
 * <p>This Spring servlet filter is a thin binding over the framework-neutral
 * {@link LocalhostGuard}: it translates the {@link HttpServletRequest} and the BootUI configuration
 * into a {@link LocalhostGuardRequest} / {@link LocalhostGuardConfig}, asks the guard to
 * {@link LocalhostGuard#decide decide}, and renders the {@link LocalhostGuardDecision}. The access
 * policy itself (trusted-source / Host allow-list / cross-site-write defenses, their evaluation
 * order, and the canonical 403 messages) lives in the engine so the Quarkus adapter shares it.</p>
 *
 * <p>The three defenses are bypassed entirely only when {@code bootui.allow-non-localhost=true}:</p>
 * <ol>
 *   <li><strong>Trusted source</strong> — the remote address must be a loopback address, fall
 *       within a range configured via {@code bootui.trusted-proxies} (CIDR notation), or equal an
 *       auto-detected container gateway when {@code bootui.trust-container-gateway} permits it (see
 *       {@link ContainerGatewayDetector}).</li>
 *   <li><strong>Host allow-list (DNS-rebinding defense)</strong> — a present {@code Host} header
 *       must resolve to a known loopback name or a configured {@code bootui.allowed-hosts} entry.</li>
 *   <li><strong>Cross-site write protection (CSRF defense)</strong> — state-changing methods are
 *       rejected on {@code Sec-Fetch-Site: cross-site} or an {@code Origin} host mismatch.</li>
 * </ol>
 *
 * <p>This binding owns the per-instance caches and side effects the guard intentionally does not:
 * the {@code bootui.trusted-proxies} parse cache, the resolve-once container-gateway snapshot, the
 * once-only "trusting container gateway" warning (emitted when a request is ultimately allowed via a
 * gateway), and the per-reason rejection logging.</p>
 *
 * <p>BootUI is a developer tool, not a production endpoint, so we fail closed by default.</p>
 */
public class LocalhostOnlyFilter extends AbstractBootUiFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    private final LocalhostGuard guard = new LocalhostGuard();

    private volatile String[] parsedFrom;
    private volatile List<CidrRange> trustedRanges = List.of();

    private final ContainerGatewayDetector gatewayDetector;

    private final Object gatewayLock = new Object();
    private volatile boolean gatewayResolved = false;
    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    public LocalhostOnlyFilter(BootUiProperties properties) {
        this(properties, new ContainerGatewayDetector());
    }

    LocalhostOnlyFilter(BootUiProperties properties, ContainerGatewayDetector gatewayDetector) {
        super(properties);
        this.gatewayDetector = gatewayDetector;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        LocalhostGuardRequest guardRequest = new LocalhostGuardRequest(
                request.getMethod(),
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("Origin"),
                request.getHeader("Sec-Fetch-Site"));

        LocalhostGuardDecision decision = guard.decide(guardRequest, buildConfig());

        if (decision instanceof Reject reject) {
            logRejection(reject, request);
            reject(response, reject.message());
            return;
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            warnTrustedGatewayOnce(allow.trustedGateway());
        }
        chain.doFilter(request, response);
    }

    /**
     * Builds the per-request guard configuration. The container-gateway snapshot is resolved (and
     * cached) lazily on the first request only when {@code bootui.trust-container-gateway} is not
     * {@code OFF}, so an {@code OFF} deployment never touches {@code /proc} or DNS.
     */
    private LocalhostGuardConfig buildConfig() {
        GatewayTrust gatewayTrust = toGatewayTrust(properties.getTrustContainerGateway());
        boolean container;
        Set<InetAddress> gateways;
        if (gatewayTrust == GatewayTrust.OFF) {
            container = false;
            gateways = Set.of();
        } else {
            resolveGatewayOnce();
            container = this.inContainer;
            gateways = this.containerGateways;
        }
        String[] allowedHosts = properties.getAllowedHosts();
        return new LocalhostGuardConfig(
                properties.isAllowNonLocalhost(),
                allowedHosts == null ? List.of() : Arrays.asList(allowedHosts),
                trustedRanges(),
                gatewayTrust,
                container,
                gateways);
    }

    private static GatewayTrust toGatewayTrust(Mode mode) {
        if (mode == null) {
            return GatewayTrust.OFF;
        }
        return switch (mode) {
            case AUTO -> GatewayTrust.AUTO;
            case ON -> GatewayTrust.ON;
            case OFF -> GatewayTrust.OFF;
        };
    }

    /**
     * Emits the once-only operator warning that a container gateway is being trusted as
     * loopback-equivalent. Mirrors the legacy message; it now fires when a request is actually
     * allowed via the gateway (rather than the instant source trust is granted), which is invisible
     * to behavior — no response status or body depends on it.
     */
    private void warnTrustedGatewayOnce(InetAddress gateway) {
        if (loggedTrustedGateway) {
            return;
        }
        loggedTrustedGateway = true;
        log.warn(
                "BootUI trusting auto-detected container gateway {} (/32) for loopback-equivalent access.",
                gateway != null ? gateway.getHostAddress() : null);
    }

    private void logRejection(Reject reject, HttpServletRequest request) {
        switch (reject.reason()) {
            case NON_LOOPBACK_SOURCE ->
                log.warn(
                        "BootUI rejected non-loopback request from {} to {}",
                        request.getRemoteAddr(),
                        request.getRequestURI());
            case DISALLOWED_HOST ->
                log.warn(
                        "BootUI rejected request with disallowed Host '{}' to {}",
                        request.getHeader("Host"),
                        request.getRequestURI());
            case CROSS_SITE_WRITE ->
                log.warn("BootUI rejected cross-site {} request to {}", request.getMethod(), request.getRequestURI());
        }
    }

    /**
     * Resolves and caches the container detection result and trusted gateways exactly once. Mirrors
     * the lazy, double-checked caching used by {@link #trustedRanges()} so the lookups happen on the
     * first relevant request rather than per request. The trusted set is the union of the route-table
     * default gateway and any Docker Desktop gateway resolved from {@code gateway.docker.internal}.
     */
    private void resolveGatewayOnce() {
        if (gatewayResolved) {
            return;
        }
        synchronized (gatewayLock) {
            if (gatewayResolved) {
                return;
            }
            inContainer = gatewayDetector.isInContainer();
            Set<InetAddress> gateways = new LinkedHashSet<>();
            gatewayDetector.defaultGateway().ifPresent(gateways::add);
            gateways.addAll(gatewayDetector.dockerDesktopGateways());
            containerGateways = Set.copyOf(gateways);
            gatewayResolved = true;
        }
    }

    /**
     * Returns the configured {@code bootui.trusted-proxies} ranges, parsing (and caching) them only
     * when the configured array changes. Malformed entries are logged once and skipped.
     */
    private List<CidrRange> trustedRanges() {
        String[] configured = properties.getTrustedProxies();
        if (configured == parsedFrom) {
            return trustedRanges;
        }
        List<CidrRange> parsed = new ArrayList<>();
        if (configured != null) {
            for (String entry : configured) {
                CidrRange range = CidrRange.parse(entry);
                if (range != null) {
                    parsed.add(range);
                } else if (entry != null && !entry.isBlank()) {
                    log.warn("BootUI ignoring malformed bootui.trusted-proxies entry '{}'", entry);
                }
            }
        }
        List<CidrRange> immutable = List.copyOf(parsed);
        trustedRanges = immutable;
        parsedFrom = configured;
        return immutable;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
