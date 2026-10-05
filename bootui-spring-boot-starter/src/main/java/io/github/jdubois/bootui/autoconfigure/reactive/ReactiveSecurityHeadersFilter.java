package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
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
        String path = pathWithinApplication(exchange.getRequest());
        ServerHttpResponse response = new SecurityHeadersResponse(exchange.getResponse(), path);
        return chain.filter(exchange.mutate().response(response).build());
    }

    private void applyHeaders(ServerHttpResponse response, String path) {
        int statusCode = response.getStatusCode() == null
                ? 200
                : response.getStatusCode().value();
        if (BootUiSecurityHeaders.removesPragma(path, properties.getApiPath(), statusCode)) {
            response.getHeaders().remove(BootUiSecurityHeaders.PRAGMA);
        }
        BootUiSecurityHeaders.headersFor(path, properties.getApiPath(), statusCode)
                .forEach((name, value) -> {
                    if (BootUiSecurityHeaders.overridesExisting(name)
                            || !response.getHeaders().containsHeader(name)) {
                        response.getHeaders().set(name, value);
                    }
                });
    }

    /**
     * Re-applies BootUI's cache policy after every downstream commit callback. Host security callbacks can
     * therefore replace the baseline, while no later callback can leave conflicting cache semantics.
     */
    private final class SecurityHeadersResponse extends ServerHttpResponseDecorator {

        private final String path;

        private SecurityHeadersResponse(ServerHttpResponse delegate, String path) {
            super(delegate);
            this.path = path;
            super.beforeCommit(this::applyPolicy);
        }

        @Override
        public void beforeCommit(Supplier<? extends Mono<Void>> action) {
            super.beforeCommit(action);
            super.beforeCommit(this::applyPolicy);
        }

        private Mono<Void> applyPolicy() {
            applyHeaders(this, path);
            return Mono.empty();
        }
    }
}
