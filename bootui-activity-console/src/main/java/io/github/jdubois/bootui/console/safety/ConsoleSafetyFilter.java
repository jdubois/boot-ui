package io.github.jdubois.bootui.console.safety;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The Activity Console's own thin {@link WebFilter} binding over the shared, framework-neutral {@link
 * LocalhostGuard} &mdash; the same local-only access policy every other BootUI adapter enforces
 * (loopback-source trust, {@code Host} allow-list / DNS-rebinding defense, cross-site-write / CSRF
 * defense), applied here to <em>every</em> request the console handles.
 *
 * <p>Unlike the host-application adapters' filters (which gate only requests under {@code /bootui/**}
 * because the rest of the host application's routes are none of BootUI's business), the console
 * <em>is</em> a single-purpose BootUI distribution: every route it serves (the UI shell, the API, the
 * activity-forward receiver) is BootUI, so this filter applies unconditionally rather than pattern
 * matching a base path first.
 *
 * <p>This class intentionally duplicates the reactive Spring adapter's {@code ReactiveLocalhostOnlyFilter}
 * (caching, lazy gateway resolution, logging) rather than depending on it, consistent with the module's
 * decision not to depend on {@code bootui-spring-autoconfigure} at all (see the package-level Javadoc).
 */
@Component
public class ConsoleSafetyFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSafetyFilter.class);

    private final ConsoleSafetyProperties properties;
    private final LocalhostGuard guard = new LocalhostGuard();
    private final ContainerGatewayDetector gatewayDetector;

    private volatile String[] parsedFrom;
    private volatile List<CidrRange> trustedRanges = List.of();

    private final Object gatewayLock = new Object();
    private volatile boolean gatewayResolved = false;
    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    @Autowired
    public ConsoleSafetyFilter(ConsoleSafetyProperties properties) {
        this(properties, new ContainerGatewayDetector());
    }

    ConsoleSafetyFilter(ConsoleSafetyProperties properties, ContainerGatewayDetector gatewayDetector) {
        this.properties = properties;
        this.gatewayDetector = gatewayDetector;
    }

    /** Runs before every other filter/handler, mirroring the {@code Integer.MIN_VALUE} order used elsewhere. */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
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
            // Not escaped: the message is an engine-owned fixed string, never attacker-controlled input.
            return writeJson(exchange, HttpStatus.FORBIDDEN, "{\"error\":\"" + reject.message() + "\"}");
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            warnTrustedGatewayOnce(allow.trustedGateway());
        }
        return chain.filter(exchange);
    }

    private String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        InetAddress address = remote.getAddress();
        return address != null ? address.getHostAddress() : null;
    }

    private LocalhostGuardConfig buildConfig() {
        GatewayTrust gatewayTrust = properties.getTrustContainerGateway();
        gatewayTrust = gatewayTrust == null ? GatewayTrust.OFF : gatewayTrust;
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

    private void warnTrustedGatewayOnce(InetAddress gateway) {
        if (loggedTrustedGateway) {
            return;
        }
        loggedTrustedGateway = true;
        log.warn(
                "BootUI Activity Console trusting auto-detected container gateway {} (/32) for "
                        + "loopback-equivalent access.",
                gateway != null ? gateway.getHostAddress() : null);
    }

    private void logRejection(Reject reject, ServerHttpRequest request) {
        switch (reject.reason()) {
            case NON_LOOPBACK_SOURCE ->
                log.warn(
                        "BootUI Activity Console rejected non-loopback request from {} to {}",
                        remoteAddress(request),
                        request.getPath().value());
            case DISALLOWED_HOST ->
                log.warn(
                        "BootUI Activity Console rejected request with disallowed Host '{}' to {}",
                        request.getHeaders().getFirst("Host"),
                        request.getPath().value());
            case CROSS_SITE_WRITE ->
                log.warn(
                        "BootUI Activity Console rejected cross-site {} request to {}",
                        request.getMethod(),
                        request.getPath().value());
        }
    }

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
                    log.warn("BootUI Activity Console ignoring malformed bootui.trusted-proxies entry '{}'", entry);
                }
            }
        }
        List<CidrRange> immutable = List.copyOf(parsed);
        trustedRanges = immutable;
        parsedFrom = configured;
        return immutable;
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String json) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
