package io.github.jdubois.bootui.engine.safety;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

/**
 * The resolved, framework-neutral configuration the {@link LocalhostGuard} consults per request.
 * Adapters build this from their own configuration surface and a pre-resolved container-gateway
 * snapshot.
 *
 * <p>The canonical constructor fails closed: {@code null} collections become empty and a
 * {@code null} {@link GatewayTrust} becomes {@link GatewayTrust#OFF}. Collections are stored as
 * supplied (not defensively copied) so that {@code allowedHosts} may legitimately contain
 * {@code null} elements — the guard tolerates them.</p>
 *
 * @param allowNonLocalhost when {@code true} the guard bypasses every check (the
 *     {@code bootui.allow-non-localhost} escape hatch)
 * @param allowedHosts the configured {@code bootui.allowed-hosts} entries (the guard additionally
 *     allows the built-in loopback host names); may contain {@code null}/untrimmed entries
 * @param trustedRanges pre-parsed {@code bootui.trusted-proxies} CIDR ranges
 * @param gatewayTrust whether (and how) to trust a detected container gateway
 * @param inContainer whether container heuristics indicate the JVM is running inside a container
 * @param detectedGateways the pre-resolved set of trusted container gateway addresses (empty when
 *     none detected or detection is disabled)
 */
public record LocalhostGuardConfig(
        boolean allowNonLocalhost,
        List<String> allowedHosts,
        List<CidrRange> trustedRanges,
        GatewayTrust gatewayTrust,
        boolean inContainer,
        Set<InetAddress> detectedGateways) {

    public LocalhostGuardConfig {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        trustedRanges = trustedRanges == null ? List.of() : trustedRanges;
        detectedGateways = detectedGateways == null ? Set.of() : detectedGateways;
        gatewayTrust = gatewayTrust == null ? GatewayTrust.OFF : gatewayTrust;
    }
}
