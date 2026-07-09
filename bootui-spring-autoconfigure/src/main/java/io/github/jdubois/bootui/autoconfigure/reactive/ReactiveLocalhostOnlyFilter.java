package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostGuardConfigSupport;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 * <p>The guard-config translation (caching / lazy gateway resolution) is shared with the servlet
 * filter via {@link LocalhostGuardConfigSupport}, since none of it touches a servlet or reactive
 * request type. Only the request/response glue that genuinely differs by transport &mdash; reading the
 * {@link ServerHttpRequest}, and per-reason rejection logging &mdash; stays local to this class,
 * mirroring how the Quarkus Vert.x binding remains its own independent translation layer over the same
 * guard.</p>
 */
public class ReactiveLocalhostOnlyFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ReactiveLocalhostOnlyFilter.class);

    private final LocalhostGuard guard = new LocalhostGuard();
    private final LocalhostGuardConfigSupport configSupport;

    public ReactiveLocalhostOnlyFilter(BootUiProperties properties) {
        this(properties, new ContainerGatewayDetector());
    }

    ReactiveLocalhostOnlyFilter(BootUiProperties properties, ContainerGatewayDetector gatewayDetector) {
        super(properties);
        this.configSupport = new LocalhostGuardConfigSupport(properties, gatewayDetector);
    }

    /**
     * Matches the servlet filter's {@code FilterRegistrationBean} order ({@code Integer.MIN_VALUE + 1}),
     * one after {@link ReactiveSecurityHeadersFilter}: WebFlux has no registration-level ordering, so
     * this is the sole ordering mechanism.
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 1;
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

        LocalhostGuardDecision decision = guard.decide(guardRequest, configSupport.buildConfig(log));

        if (decision instanceof Reject reject) {
            logRejection(reject, request);
            // Not escaped: mirrors LocalhostOnlyFilter.reject(...), the message is an engine-owned
            // fixed string, never attacker-controlled input.
            return writeJson(exchange, HttpStatus.FORBIDDEN, "{\"error\":\"" + reject.message() + "\"}");
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            configSupport.warnTrustedGatewayOnce(log, allow.trustedGateway());
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
}
