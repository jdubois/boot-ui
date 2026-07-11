package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive authentication binding for non-loopback BootUI API requests.
 *
 * <p>Trust is delegated to {@link ReactiveLocalhostOnlyFilter#isTrustedSource(String)} rather than
 * re-derived here: a source already trusted via loopback, {@code bootui.trusted-proxies}, or
 * {@code bootui.trust-container-gateway} is treated identically here, so operators who already opted
 * into one of those trust mechanisms keep frictionless access instead of also being forced through the
 * bearer-token/unlock flow.</p>
 */
public final class ReactiveApiAuthenticationFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private static final String SESSION_PATH = "/auth/session";

    private final ApiTokenAuthenticator authenticator;
    private final ReactiveLocalhostOnlyFilter localhostOnlyFilter;

    public ReactiveApiAuthenticationFilter(
            BootUiProperties properties,
            ApiTokenAuthenticator authenticator,
            ReactiveLocalhostOnlyFilter localhostOnlyFilter) {
        super(properties);
        this.authenticator = authenticator;
        this.localhostOnlyFilter = localhostOnlyFilter;
    }

    /**
     * This filter keeps the {@code Integer.MIN_VALUE + 2} slot that {@link ReactivePanelAccessFilter}
     * previously (and, briefly, also) used, which caused an unspecified-order collision between the
     * two; {@link ReactivePanelAccessFilter} has since been bumped to {@code Integer.MIN_VALUE + 3} so
     * this filter always runs <em>before</em> it, ensuring an unauthenticated remote caller gets a 401
     * rather than leaking panel-availability information via a 403. See {@link
     * ReactivePanelAccessFilter#getOrder()}.
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 2;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return !isBootUiApiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean trustedSource = localhostOnlyFilter.isTrustedSource(remoteAddress(request));
        boolean authorized = authenticator.isAuthorized(
                trustedSource,
                request.getHeaders().getFirst("Authorization"),
                request.getHeaders().getFirst("Cookie"));
        if (!authorized) {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", ApiTokenAuthenticator.AUTHENTICATION_CHALLENGE);
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            return writeJson(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "{\"error\":\"" + ApiTokenAuthenticator.AUTHENTICATION_REQUIRED_MESSAGE + "\"}");
        }

        if (isSessionRequest(request)) {
            if (!trustedSource) {
                exchange.getResponse()
                        .addCookie(ResponseCookie.from(ApiTokenAuthenticator.SESSION_COOKIE_NAME, authenticator.token())
                                .path(withoutTrailingSlash(properties.getApiPath()))
                                .httpOnly(true)
                                .sameSite("Strict")
                                .secure("https"
                                        .equalsIgnoreCase(request.getURI().getScheme()))
                                .build());
            }
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private boolean isSessionRequest(ServerHttpRequest request) {
        return request.getMethod() != null
                && "POST".equals(request.getMethod().name())
                && request.getPath()
                        .pathWithinApplication()
                        .value()
                        .equals(withoutTrailingSlash(properties.getApiPath()) + SESSION_PATH);
    }

    private static String withoutTrailingSlash(String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        InetAddress address = remote.getAddress();
        return address != null ? address.getHostAddress() : null;
    }
}
