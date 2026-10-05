package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.engine.safety.CidrRange;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Shared {@link LocalhostGuardConfig} construction for the servlet {@code LocalhostOnlyFilter} and the
 * WebFlux {@code ReactiveLocalhostOnlyFilter}. Both bindings need the exact same lazy, cached
 * translation of {@link BootUiProperties} (plus the auto-detected container gateway) into a guard
 * config, and none of that translation touches a servlet or reactive request/response type, so it is
 * extracted once here rather than duplicated per transport.
 *
 * <p>Owns the per-instance caches the engine guard intentionally does not: the
 * {@code bootui.trusted-proxies} parse cache and the resolve-once container-gateway snapshot. Each
 * filter constructs and keeps its own instance (no shared mutable state across the two transports).
 * Logging call sites accept the caller's own {@link Logger} so log lines keep attributing to the
 * concrete filter class exactly as they did before this extraction.</p>
 */
public final class LocalhostGuardConfigSupport {

    private final BootUiProperties properties;
    private final ContainerGatewayDetector gatewayDetector;

    private volatile String[] parsedFrom;
    private volatile List<CidrRange> trustedRanges = List.of();

    private final Object gatewayLock = new Object();
    private volatile boolean gatewayResolved = false;
    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    public LocalhostGuardConfigSupport(BootUiProperties properties, ContainerGatewayDetector gatewayDetector) {
        this.properties = properties;
        this.gatewayDetector = gatewayDetector;
    }

    /**
     * Builds the per-request guard configuration. The container-gateway snapshot is resolved (and
     * cached) lazily on the first request only when {@code bootui.trust-container-gateway} is not
     * {@code OFF}, so an {@code OFF} deployment never touches {@code /proc} or DNS.
     */
    public LocalhostGuardConfig buildConfig(Logger log) {
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
                trustedRanges(log),
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
     * loopback-equivalent, logged through the caller's own {@link Logger} so the line still attributes
     * to the concrete filter class. Fires when a request is actually allowed via the gateway; invisible
     * to behavior since no response status or body depends on it.
     */
    public void warnTrustedGatewayOnce(Logger log, InetAddress gateway) {
        if (loggedTrustedGateway) {
            return;
        }
        loggedTrustedGateway = true;
        log.warn(
                "BootUI trusting auto-detected container gateway {} (/32) for loopback-equivalent access.",
                gateway != null ? gateway.getHostAddress() : null);
    }

    /**
     * Resolves and caches the container detection result and trusted gateways exactly once. Mirrors
     * the lazy, double-checked caching used by {@link #trustedRanges} so the lookups happen on the
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
     * when the configured array changes. Malformed entries are logged once (through the caller's own
     * {@link Logger}) and skipped.
     */
    private List<CidrRange> trustedRanges(Logger log) {
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
