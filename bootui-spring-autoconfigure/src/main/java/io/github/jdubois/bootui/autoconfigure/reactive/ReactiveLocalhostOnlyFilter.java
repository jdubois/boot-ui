package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.engine.safety.CidrRange;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code LocalhostOnlyFilter}: the exact same thin binding over the
 * framework-neutral {@link LocalhostGuard}, translating a {@link ServerWebExchange} instead of an
 * {@code HttpServletRequest}/{@code HttpServletResponse} pair. See {@code LocalhostOnlyFilter}'s
 * Javadoc for the full policy write-up (trusted-source / Host allow-list / cross-site-write defenses,
 * their order, and the canonical 403 message) &mdash; that policy is unchanged here, it lives entirely
 * in the engine {@link LocalhostGuard}.
 *
 * <p>This class intentionally duplicates the servlet filter's caching / lazy-gateway-resolution /
 * logging rather than sharing a common servlet+reactive base class, mirroring how the Quarkus Vert.x
 * binding is its own independent translation layer over the same guard.</p>
 */
public class ReactiveLocalhostOnlyFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ReactiveLocalhostOnlyFilter.class);

    private final LocalhostGuard guard = new LocalhostGuard();

    private volatile String[] parsedFrom;
    private volatile List<CidrRange> trustedRanges = List.of();

    private final ContainerGatewayDetector gatewayDetector;

    private final Object gatewayLock = new Object();
    private volatile boolean gatewayResolved = false;
    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    public ReactiveLocalhostOnlyFilter(BootUiProperties properties) {
        this(properties, new ContainerGatewayDetector());
    }

    ReactiveLocalhostOnlyFilter(BootUiProperties properties, ContainerGatewayDetector gatewayDetector) {
        super(properties);
        this.gatewayDetector = gatewayDetector;
    }

    /**
     * Matches the servlet filter's {@code FilterRegistrationBean} order ({@code Integer.MIN_VALUE}):
     * WebFlux has no registration-level ordering, so this is the sole ordering mechanism.
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return !isBootUiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        LocalhostGuardRequest guardRequest = new LocalhostGuardRequest(
                request.getMethod() != null ? request.getMethod().name() : null,
                remoteAddress(request),
                request.getHeaders().getFirst("Host"),
                request.getHeaders().getFirst("Origin"),
                request.getHeaders().getFirst("Sec-Fetch-Site"));

        LocalhostGuardDecision decision = guard.decide(guardRequest, buildConfig());

        if (decision instanceof Reject reject) {
            logRejection(reject, request);
            // Not escaped: mirrors LocalhostOnlyFilter.reject(...), the message is an engine-owned
            // fixed string, never attacker-controlled input.
            return writeJson(exchange, HttpStatus.FORBIDDEN, "{\"error\":\"" + reject.message() + "\"}");
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            warnTrustedGatewayOnce(allow.trustedGateway());
        }
        return chain.filter(exchange);
    }

    /**
     * The raw TCP peer address of the socket, mirroring {@code HttpServletRequest#getRemoteAddr()}.
     * Never a forwarded header &mdash; matches the guard's contract that {@code remoteAddr} is the
     * actual peer, not client-supplied.
     */
    private String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        InetAddress address = remote.getAddress();
        return address != null ? address.getHostAddress() : null;
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
     * loopback-equivalent. Fires when a request is actually allowed via the gateway; invisible to
     * behavior since no response status or body depends on it.
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

    private void logRejection(Reject reject, ServerHttpRequest request) {
        switch (reject.reason()) {
            case NON_LOOPBACK_SOURCE ->
                log.warn(
                        "BootUI rejected non-loopback request from {} to {}",
                        remoteAddress(request),
                        request.getPath().value());
            case DISALLOWED_HOST ->
                log.warn(
                        "BootUI rejected request with disallowed Host '{}' to {}",
                        request.getHeaders().getFirst("Host"),
                        request.getPath().value());
            case CROSS_SITE_WRITE ->
                log.warn(
                        "BootUI rejected cross-site {} request to {}",
                        request.getMethod(),
                        request.getPath().value());
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
}
