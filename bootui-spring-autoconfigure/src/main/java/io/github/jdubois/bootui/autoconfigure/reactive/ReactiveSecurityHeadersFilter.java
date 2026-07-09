package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code SecurityHeadersFilter}: applies the same BootUI response-header
 * security policy over a {@link ServerWebExchange} instead of an
 * {@code HttpServletRequest}/{@code HttpServletResponse} pair.
 *
 * <p>Registered at order {@code Integer.MIN_VALUE} — before {@link ReactiveLocalhostOnlyFilter} and
 * {@link ReactivePanelAccessFilter} — so the headers are present on <em>all</em> BootUI responses
 * including 403 rejections produced by those downstream filters. The header policy itself is defined in
 * the framework-neutral {@link BootUiSecurityHeaders} engine class shared with both the servlet filter
 * and the Quarkus adapter.</p>
 */
public class ReactiveSecurityHeadersFilter extends AbstractReactiveBootUiFilter implements Ordered {

    public ReactiveSecurityHeadersFilter(BootUiProperties properties) {
        super(properties);
    }

    /**
     * Runs before {@link ReactiveLocalhostOnlyFilter} (order {@code Integer.MIN_VALUE + 1}) so security
     * headers are set on every BootUI response including rejections.
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return !isBootUiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        applyHeaders(exchange.getRequest(), exchange.getResponse());
        return chain.filter(exchange);
    }

    private void applyHeaders(ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().set(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, BootUiSecurityHeaders.CSP_VALUE);
        response.getHeaders().set(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS, BootUiSecurityHeaders.NOSNIFF);
        response.getHeaders().set(BootUiSecurityHeaders.X_FRAME_OPTIONS, BootUiSecurityHeaders.DENY);
        response.getHeaders()
                .set(BootUiSecurityHeaders.REFERRER_POLICY, BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);

        String path = pathWithinApplication(request);
        String cacheControl = BootUiSecurityHeaders.cacheControl(path, properties.getApiPath());
        response.getHeaders().set(BootUiSecurityHeaders.CACHE_CONTROL, cacheControl);
        if (BootUiSecurityHeaders.shouldSetPragma(cacheControl)) {
            response.getHeaders().set(BootUiSecurityHeaders.PRAGMA, BootUiSecurityHeaders.PRAGMA_NO_CACHE);
        }
    }
}
