package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects any BootUI request that does not originate from the local machine.
 *
 * <p>The filter enforces three independent defenses and is bypassed entirely only when
 * {@code bootui.allow-non-localhost=true}:</p>
 * <ol>
 *   <li><strong>Trusted source</strong> — the remote address must be a loopback address, fall
 *       within a range configured via {@code bootui.trusted-proxies} (CIDR notation), or equal the
 *       auto-detected container default gateway when {@code bootui.trust-container-gateway} permits
 *       it (see {@link ContainerGatewayDetector}). Each of these is an opt-in narrow relaxation for
 *       local Docker-bridge callers: it widens only this source check and leaves the Host allow-list
 *       and cross-site write protection below fully in force, unlike the all-or-nothing
 *       {@code bootui.allow-non-localhost}.</li>
 *   <li><strong>Host allow-list (DNS-rebinding defense)</strong> — when a {@code Host} header is
 *       present it must resolve to a known loopback name or a configured
 *       {@code bootui.allowed-hosts} entry. A browser victim of a DNS-rebinding attack always sends
 *       the attacker's hostname in {@code Host}, so this blocks the rebinding even though the socket
 *       still terminates on loopback. A missing {@code Host} header is allowed: browsers always set
 *       it (and cannot suppress it from script), so its absence indicates a non-browser local client
 *       rather than an attack.</li>
 *   <li><strong>Cross-site write protection (CSRF defense)</strong> — for state-changing methods the
 *       request is rejected when {@code Sec-Fetch-Site: cross-site} is present, or when an
 *       {@code Origin} header is present and its host does not match the request host. This protects
 *       mutating endpoints even when Spring Security (and its CSRF tokens) is not on the classpath.</li>
 * </ol>
 *
 * <p>BootUI is a developer tool, not a production endpoint, so we fail closed by default.</p>
 */
public class LocalhostOnlyFilter extends AbstractBootUiFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    private static final Set<String> BUILT_IN_ALLOWED_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private volatile String[] parsedFrom;
    private volatile List<CidrRange> trustedRanges = List.of();

    private final ContainerGatewayDetector gatewayDetector;

    private final Object gatewayLock = new Object();
    private volatile boolean gatewayResolved = false;
    private volatile boolean inContainer = false;
    private volatile InetAddress containerGateway;
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

        if (properties.isAllowNonLocalhost()) {
            chain.doFilter(request, response);
            return;
        }

        String remote = request.getRemoteAddr();
        if (!isTrustedSource(remote)) {
            reject(
                    response,
                    "BootUI is restricted to loopback requests. Set bootui.allow-non-localhost=true to override, "
                            + "or add a trusted source range to bootui.trusted-proxies.",
                    "non-loopback request from {} to {}",
                    remote,
                    request.getRequestURI());
            return;
        }

        String hostHeader = request.getHeader("Host");
        String requestHost = extractHost(hostHeader);
        if (requestHost != null && !isAllowedHost(requestHost)) {
            reject(
                    response,
                    "BootUI rejected an unrecognized Host header. "
                            + "Add it to bootui.allowed-hosts to allow this hostname.",
                    "request with disallowed Host '{}' to {}",
                    hostHeader,
                    request.getRequestURI());
            return;
        }

        if (!isSafeMethod(request) && isCrossSiteWrite(request, requestHost)) {
            reject(
                    response,
                    "BootUI rejected a cross-site request to a state-changing endpoint.",
                    "cross-site {} request to {}",
                    request.getMethod(),
                    request.getRequestURI());
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isSafeMethod(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    private boolean isCrossSiteWrite(HttpServletRequest request, String requestHost) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return false;
        }
        // Compare host only (not scheme/port) on purpose: the remote cross-site threat is already blocked by the Host
        // allow-list, and a stricter port match would break the supported Vite dev-server proxy (browser Origin
        // localhost:5173 proxied to a Host of localhost:8080) for state-changing actions.
        String originHost = extractHost(origin);
        return originHost == null || !originHost.equalsIgnoreCase(requestHost);
    }

    private boolean isAllowedHost(String host) {
        if (BUILT_IN_ALLOWED_HOSTS.contains(host)) {
            return true;
        }
        String[] configured = properties.getAllowedHosts();
        if (configured != null) {
            for (String allowed : configured) {
                if (allowed != null && host.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extracts the lowercased host from a {@code Host} or {@code Origin} header value, stripping any
     * scheme, port, path, and IPv6 brackets. Returns {@code null} for blank or malformed input.
     */
    private String extractHost(String value) {
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

    private boolean isTrustedSource(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException e) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        for (CidrRange range : trustedRanges()) {
            if (range.contains(address)) {
                return true;
            }
        }
        return isTrustedContainerGateway(address);
    }

    /**
     * Returns {@code true} when {@code address} equals the auto-detected container default gateway
     * and {@code bootui.trust-container-gateway} permits trusting it. This relaxes only the
     * source-address check by a single {@code /32}; the Host allow-list and cross-site write
     * defenses still apply. The mode is read per request (so tests can flip it), while the
     * underlying container detection and gateway lookup are resolved from the filesystem once and
     * cached.
     *
     * <ul>
     *   <li>{@code OFF} (default) — never trust the gateway.</li>
     *   <li>{@code AUTO} — trust it only when container heuristics indicate we are running inside a
     *       container.</li>
     *   <li>{@code ON} — trust it whenever a default gateway was detected, even if the container
     *       heuristics were inconclusive.</li>
     * </ul>
     */
    private boolean isTrustedContainerGateway(InetAddress address) {
        Mode mode = properties.getTrustContainerGateway();
        if (mode == Mode.OFF) {
            return false;
        }
        resolveGatewayOnce();
        InetAddress gateway = containerGateway;
        if (gateway == null) {
            return false;
        }
        if (mode == Mode.AUTO && !inContainer) {
            return false;
        }
        if (!gateway.equals(address)) {
            return false;
        }
        if (!loggedTrustedGateway) {
            loggedTrustedGateway = true;
            log.info(
                    "BootUI trusting auto-detected container gateway {} (/32) for loopback-equivalent access.",
                    gateway.getHostAddress());
        }
        return true;
    }

    /**
     * Resolves and caches the container detection result and default gateway exactly once. Mirrors
     * the lazy, double-checked caching used by {@link #trustedRanges()} so the filesystem reads
     * happen on the first relevant request rather than per request.
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
            containerGateway = gatewayDetector.defaultGateway().orElse(null);
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

    private void reject(HttpServletResponse response, String message, String logFormat, Object... logArgs)
            throws IOException {
        log.warn("BootUI rejected " + logFormat, logArgs);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
