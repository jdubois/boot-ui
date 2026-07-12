package io.github.jdubois.bootui.engine.safety;

import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reason;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Framework-neutral decision engine that decides whether a BootUI request may proceed. This is the
 * single source of truth for BootUI's local-only access policy; the Spring and Quarkus adapters are
 * thin bindings that translate their native request/configuration into {@link LocalhostGuardRequest}
 * / {@link LocalhostGuardConfig}, call {@link #decide(LocalhostGuardRequest, LocalhostGuardConfig)},
 * and render the {@link LocalhostGuardDecision}.
 *
 * <p>The guard enforces three independent defenses, evaluated in this order (the order is itself
 * behavior — it determines which 403 message a request that fails multiple checks receives), and is
 * bypassed entirely only when {@link LocalhostGuardConfig#allowNonLocalhost()} is {@code true}:</p>
 * <ol>
 *   <li><strong>Trusted source</strong> — the raw TCP peer address must be loopback, fall within a
 *       configured trusted CIDR range, or equal an auto-detected container gateway when the
 *       {@link GatewayTrust} mode permits it. The peer address is always the real socket address,
 *       never a forwarded header.</li>
 *   <li><strong>Host allow-list (DNS-rebinding defense)</strong> — when a {@code Host} header is
 *       present it must resolve to a built-in loopback name or a configured allow-list entry. A
 *       missing {@code Host} header is allowed (browsers always set it).</li>
 *   <li><strong>Cross-site write protection (CSRF defense)</strong> — for state-changing methods the
 *       request is rejected when {@code Sec-Fetch-Site: cross-site} is present, or when an
 *       {@code Origin} header is present and its host does not match the request host (host-only
 *       comparison, so the supported {@code :5173}&#8594;{@code :8080} Vite proxy still works).</li>
 * </ol>
 *
 * <p>The guard is stateless and performs no logging. It does call {@link InetAddress#getByName} on
 * the raw peer address exactly as the legacy filter did; for the real (numeric) peer-address domain
 * this performs no DNS lookup and the result is deterministic.</p>
 */
public final class LocalhostGuard {

    /** Canonical 403 body message when the source address is not trusted. */
    public static final String MESSAGE_NON_LOOPBACK_SOURCE =
            "BootUI is restricted to loopback requests. Set bootui.allow-non-localhost=true to override, "
                    + "add a trusted source range to bootui.trusted-proxies, or (when running in a container) "
                    + "set bootui.trust-container-gateway=AUTO to trust the auto-detected container gateway.";

    /** Canonical 403 body message when a present {@code Host} header is not on the allow-list. */
    public static final String MESSAGE_DISALLOWED_HOST =
            "BootUI rejected an unrecognized Host header. Add it to bootui.allowed-hosts to allow this hostname.";

    /** Canonical 403 body message when a state-changing request is cross-site. */
    public static final String MESSAGE_CROSS_SITE_WRITE =
            "BootUI rejected a cross-site request to a state-changing endpoint.";

    private static final Set<String> BUILT_IN_ALLOWED_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Shared immutable allow result for direct trust (loopback or a trusted range). */
    private static final Allow ALLOW_DIRECT = new Allow(true, false, null);

    /**
     * Shared immutable allow result for the {@code bootui.allow-non-localhost} bypass, which skips the
     * source check entirely rather than genuinely trusting the peer. {@link Allow#trustedSource()} is
     * {@code false} here so callers that key additional protections (e.g. bearer-token authentication)
     * off genuine source trust don't mistake the bypass for it.
     */
    private static final Allow ALLOW_BYPASS = new Allow(false, false, null);

    /**
     * Evaluates the local-only access policy for a single request.
     *
     * @return {@link Allow} when the request may proceed (carrying any container-gateway trust
     *     detail), or {@link Reject} with the typed reason and canonical 403 message otherwise
     */
    public LocalhostGuardDecision decide(LocalhostGuardRequest request, LocalhostGuardConfig config) {
        if (config.allowNonLocalhost()) {
            return ALLOW_BYPASS;
        }

        Allow sourceTrust = trustedSource(request.remoteAddr(), config);
        if (sourceTrust == null) {
            return new Reject(Reason.NON_LOOPBACK_SOURCE, MESSAGE_NON_LOOPBACK_SOURCE);
        }

        String requestHost = extractHost(request.hostAuthority());
        if (requestHost != null && !isAllowedHost(requestHost, config)) {
            return new Reject(Reason.DISALLOWED_HOST, MESSAGE_DISALLOWED_HOST);
        }

        if (!isSafeMethod(request.method()) && isCrossSiteWrite(request, requestHost)) {
            return new Reject(Reason.CROSS_SITE_WRITE, MESSAGE_CROSS_SITE_WRITE);
        }

        return sourceTrust;
    }

    /**
     * Returns whether {@code remoteAddr} is a genuinely trusted source under {@code config} — loopback,
     * a configured trusted range, or a trusted container gateway — independent of
     * {@link LocalhostGuardConfig#allowNonLocalhost()} and the Host allow-list / cross-site-write
     * checks. This is the single source of truth other BootUI protections (such as
     * {@code ApiTokenAuthenticator}'s bearer-token requirement for non-loopback API callers) should
     * consult instead of re-deriving their own, narrower notion of "local" — so a deployment that
     * opted into {@code bootui.trusted-proxies} or {@code bootui.trust-container-gateway} to get
     * frictionless access from a non-loopback source gets that same frictionless treatment everywhere,
     * not just past the {@link #decide} check.
     */
    public boolean isTrustedSource(String remoteAddr, LocalhostGuardConfig config) {
        return trustedSource(remoteAddr, config) != null;
    }

    /**
     * Returns the {@link Allow} describing how the source was trusted, or {@code null} when the
     * source is not trusted. Loopback and trusted ranges yield {@link #ALLOW_DIRECT}; a matched
     * container gateway yields an {@link Allow} carrying the gateway address.
     */
    private Allow trustedSource(String remoteAddr, LocalhostGuardConfig config) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return null;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException e) {
            return null;
        }
        if (address.isLoopbackAddress()) {
            return ALLOW_DIRECT;
        }
        for (CidrRange range : config.trustedRanges()) {
            if (range.contains(address)) {
                return ALLOW_DIRECT;
            }
        }
        return trustedContainerGateway(address, config);
    }

    /**
     * Returns an {@link Allow} carrying {@code address} when it equals one of the pre-resolved
     * container gateways and the {@link GatewayTrust} mode permits trusting it, otherwise
     * {@code null}. Mirrors the legacy precedence exactly: {@code OFF} first, then an empty gateway
     * set, then {@code AUTO} without container heuristics, then a non-matching address.
     */
    private Allow trustedContainerGateway(InetAddress address, LocalhostGuardConfig config) {
        GatewayTrust mode = config.gatewayTrust();
        if (mode == GatewayTrust.OFF) {
            return null;
        }
        Set<InetAddress> gateways = config.detectedGateways();
        if (gateways.isEmpty()) {
            return null;
        }
        if (mode == GatewayTrust.AUTO && !config.inContainer()) {
            return null;
        }
        if (!gateways.contains(address)) {
            return null;
        }
        return new Allow(true, true, address);
    }

    private static boolean isSafeMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    private static boolean isCrossSiteWrite(LocalhostGuardRequest request, String requestHost) {
        String fetchSite = request.secFetchSite();
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return true;
        }
        String origin = request.origin();
        if (origin == null || origin.isBlank()) {
            return false;
        }
        // Compare host only (not scheme/port) on purpose: the remote cross-site threat is already blocked by the Host
        // allow-list, and a stricter port match would break the supported Vite dev-server proxy (browser Origin
        // localhost:5173 proxied to a Host of localhost:8080) for state-changing actions.
        String originHost = extractHost(origin);
        return originHost == null || !originHost.equalsIgnoreCase(requestHost);
    }

    private static boolean isAllowedHost(String host, LocalhostGuardConfig config) {
        if (BUILT_IN_ALLOWED_HOSTS.contains(host)) {
            return true;
        }
        for (String allowed : config.allowedHosts()) {
            if (allowed != null && host.equalsIgnoreCase(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the lowercased host from a {@code Host} or {@code Origin} header value, stripping any
     * scheme, port, path, and IPv6 brackets. Returns {@code null} for blank or malformed input. This
     * is the single host parser shared by the Host allow-list and the cross-site Origin comparison,
     * and across both adapters.
     */
    static String extractHost(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        int scheme = candidate.indexOf("://");
        if (scheme >= 0) {
            candidate = candidate.substring(scheme + 3);
        }
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(0, slash);
        }
        if (candidate.isEmpty()) {
            return null;
        }
        String host;
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = candidate.substring(1, close);
        } else {
            int colon = candidate.indexOf(':');
            host = colon >= 0 ? candidate.substring(0, colon) : candidate;
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        return host.isEmpty() ? null : host;
    }
}
