package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects any BootUI request that does not originate from the local machine.
 *
 * <p>This Spring servlet filter is a thin binding over the framework-neutral
 * {@link LocalhostGuard}: it translates the {@link HttpServletRequest} and the BootUI configuration
 * into a {@link LocalhostGuardRequest} / {@link io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig},
 * asks the guard to {@link LocalhostGuard#decide decide}, and renders the {@link LocalhostGuardDecision}.
 * The access policy itself (trusted-source / Host allow-list / cross-site-write defenses, their
 * evaluation order, and the canonical 403 messages) lives in the engine so the Quarkus adapter shares
 * it.</p>
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
 * <p>The per-instance caches and side effects the guard intentionally does not own (the
 * {@code bootui.trusted-proxies} parse cache, the resolve-once container-gateway snapshot, and the
 * once-only "trusting container gateway" warning) live in {@link LocalhostGuardConfigSupport}, shared
 * with the WebFlux sibling {@code ReactiveLocalhostOnlyFilter} since none of that translation touches a
 * servlet or reactive request type. This class keeps only the per-reason rejection logging, which does
 * read the servlet request.</p>
 *
 * <p>BootUI is a developer tool, not a production endpoint, so we fail closed by default.</p>
 */
public class LocalhostOnlyFilter extends AbstractBootUiFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    private final LocalhostGuard guard = new LocalhostGuard();
    private final LocalhostGuardConfigSupport configSupport;

    public LocalhostOnlyFilter(BootUiProperties properties) {
        this(properties, new ContainerGatewayDetector());
    }

    LocalhostOnlyFilter(BootUiProperties properties, ContainerGatewayDetector gatewayDetector) {
        super(properties);
        this.configSupport = new LocalhostGuardConfigSupport(properties, gatewayDetector);
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

        LocalhostGuardDecision decision = guard.decide(guardRequest, configSupport.buildConfig(log));

        if (decision instanceof Reject reject) {
            logRejection(reject, request);
            reject(response, reject.message());
            return;
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            configSupport.warnTrustedGatewayOnce(log, allow.trustedGateway());
        }
        chain.doFilter(request, response);
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

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
