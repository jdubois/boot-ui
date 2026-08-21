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
 * always enforced. {@link LocalhostGuardConfig#allowNonLocalhost()} bypasses only the trusted-source
 * check; Host and cross-site-write defenses remain active:</p>
 * <ol>
 *   <li><strong>Trusted source</strong> — the raw TCP peer address must be loopback, fall within a
 *       configured trusted CIDR range, or equal an auto-detected container gateway when the
 *       {@link GatewayTrust} mode permits it. The peer address is always the real socket address,
 *       never a forwarded header.</li>
 *   <li><strong>Host allow-list (DNS-rebinding defense)</strong> — when a {@code Host} header is
 *       present it must parse as a well-formed {@code host[:port]} authority <em>and</em> resolve to
 *       a built-in loopback name or a configured allow-list entry. A present but malformed value (for
 *       example {@code :}, {@code [::1]junk}, or {@code http://localhost:8080}) is rejected exactly
 *       like a disallowed hostname; only a genuinely missing or blank {@code Host} header is allowed
 *       (browsers always set it).</li>
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

    /**
     * Canonical 403 body message when a present {@code Host} header is malformed or not on the
     * allow-list.
     */
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
     * Shared immutable allow result for the {@code bootui.allow-non-localhost} source bypass.
     * {@link Allow#trustedSource()} is {@code false} so bearer-token authentication still applies.
     */
    private static final Allow ALLOW_BYPASS = new Allow(false, false, null);

    /**
     * Evaluates the local-only access policy for a single request.
     *
     * @return {@link Allow} when the request may proceed (carrying any container-gateway trust
     *     detail), or {@link Reject} with the typed reason and canonical 403 message otherwise
     */
    public LocalhostGuardDecision decide(LocalhostGuardRequest request, LocalhostGuardConfig config) {
        Allow sourceTrust;
        if (config.allowNonLocalhost()) {
            sourceTrust = ALLOW_BYPASS;
        } else {
            sourceTrust = trustedSource(request.remoteAddr(), config);
            if (sourceTrust == null) {
                return new Reject(Reason.NON_LOOPBACK_SOURCE, MESSAGE_NON_LOOPBACK_SOURCE);
            }
        }

        // A present Host header must survive parsing *and* the allow-list: a malformed authority is
        // rejected rather than falling through to the intentional missing-Host allowance, which only
        // applies when the header is genuinely absent or blank.
        String requestHost = extractRequestHost(request.hostAuthority());
        if (isHostPresent(request.hostAuthority()) && (requestHost == null || !isAllowedHost(requestHost, config))) {
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
        String originHost = extractOriginHost(origin);
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
     * Returns whether a {@code Host} header value is present at all — a non-{@code null}, non-blank
     * value. This is deliberately independent of whether the value <em>parses</em>: the missing-Host
     * allowance must apply only to genuinely absent or blank headers, never to malformed ones.
     */
    private static boolean isHostPresent(String hostAuthority) {
        return hostAuthority != null && !hostAuthority.isBlank();
    }

    /**
     * Parses a {@code Host} (or HTTP/2 {@code :authority}) header value into its lowercased host.
     * The value must be authority-form — {@code host[:port]} or {@code [IPv6][:port]} — so a scheme
     * ({@code http://localhost}), a path ({@code localhost:8080/x}), userinfo, an empty host
     * ({@code :}), trailing junk after the IPv6 brackets ({@code [::1]junk}), a non-numeric port, or
     * any character that cannot appear in a hostname yields {@code null}. Callers must treat
     * {@code null} as "no usable host" and fail closed rather than as "no Host header".
     */
    static String extractRequestHost(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        return candidate.isEmpty() ? null : parseAuthority(candidate);
    }

    /**
     * Parses an {@code Origin} header value into its lowercased host. Unlike a {@code Host} header an
     * Origin is a serialized origin, so the {@code scheme://} prefix is expected and a trailing path
     * (sent by some non-browser clients) is tolerated; the remaining authority is then parsed by the
     * same strict {@link #parseAuthority} used for {@code Host}, so both defenses share one parser
     * across all adapters. Returns {@code null} when no authority can be read — including for the
     * opaque {@code null} origin — which the cross-site check treats as "not same-site".
     */
    static String extractOriginHost(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        int scheme = candidate.indexOf("://");
        if (scheme >= 0) {
            candidate = candidate.substring(scheme + 3);
        }
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(0, slash);
        }
        return candidate.isEmpty() ? null : parseAuthority(candidate);
    }

    /**
     * Parses a non-empty, trimmed {@code host[:port]} / {@code [IPv6][:port]} authority into its
     * lowercased host, or {@code null} when the authority is malformed.
     */
    private static String parseAuthority(String candidate) {
        String host;
        String portSuffix;
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = candidate.substring(1, close);
            portSuffix = candidate.substring(close + 1);
            if (!isIpv6Literal(host)) {
                return null;
            }
        } else {
            int colon = candidate.indexOf(':');
            host = colon >= 0 ? candidate.substring(0, colon) : candidate;
            portSuffix = colon >= 0 ? candidate.substring(colon) : "";
            if (!isRegisteredName(host)) {
                return null;
            }
        }
        if (!isPortSuffix(portSuffix)) {
            return null;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns whether the remainder that follows the host is a valid {@code [":" port]} suffix:
     * either empty or a colon followed by digits only (RFC 9110 allows an empty port). Anything else
     * — {@code [::1]junk}, {@code localhost:80x}, {@code a:b:c} — makes the authority malformed.
     */
    private static boolean isPortSuffix(String portSuffix) {
        if (portSuffix.isEmpty()) {
            return true;
        }
        if (portSuffix.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < portSuffix.length(); i++) {
            char c = portSuffix.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code host} is a plausible registered name or IPv4 literal: a non-empty run of
     * unreserved hostname characters. Rejects whitespace, {@code @}, extra colons, and anything else
     * that would make a {@code Host} header ambiguous.
     */
    private static boolean isRegisteredName(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the bracketed text is a plausible IPv6 literal: hexadecimal groups separated by
     * colons (with an optional embedded IPv4 tail), followed by an optional {@code %}-separated zone
     * id whose characters are unreserved or percent-encoded (so both the RFC 6874 {@code %25eth0}
     * form and the raw {@code %eth0} form clients sometimes send are accepted). No address arithmetic
     * is performed and no lookup is attempted; this only rejects values that cannot be an IPv6
     * literal at all, so {@code [::1]} and {@code [fe80::1%25eth0]} parse while {@code []} and
     * {@code [localhost]} do not. A syntactically impossible literal that survives still has to match
     * the allow-list, which no such value can.
     */
    private static boolean isIpv6Literal(String host) {
        int zone = host.indexOf('%');
        String address = zone >= 0 ? host.substring(0, zone) : host;
        String zoneId = zone >= 0 ? host.substring(zone + 1) : "";
        if (address.isEmpty() || address.indexOf(':') < 0) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean allowed =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return zone < 0 || isZoneId(zoneId);
    }

    /** Returns whether {@code zoneId} is a non-empty run of unreserved or percent-encoded characters. */
    private static boolean isZoneId(String zoneId) {
        return !zoneId.isEmpty() && isRegisteredName(zoneId.replace("%", ""));
    }
}
