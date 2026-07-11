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

/** Reactive authentication binding for non-loopback BootUI API requests. */
public final class ReactiveApiAuthenticationFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private static final String SESSION_PATH = "/auth/session";

    private final ApiTokenAuthenticator authenticator;

    public ReactiveApiAuthenticationFilter(BootUiProperties properties, ApiTokenAuthenticator authenticator) {
        super(properties);
        this.authenticator = authenticator;
    }

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
        String remoteAddress = remoteAddress(request);
        boolean authorized = authenticator.isAuthorized(
                remoteAddress,
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
            if (!authenticator.isLoopback(remoteAddress)) {
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
