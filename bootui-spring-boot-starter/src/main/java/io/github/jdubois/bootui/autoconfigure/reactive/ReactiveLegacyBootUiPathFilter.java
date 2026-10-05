package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive guard for the internal classpath-static {@code /bootui} mount when a custom path is active.
 */
public final class ReactiveLegacyBootUiPathFilter implements WebFilter, Ordered {

    private final BootUiProperties properties;

    public ReactiveLegacyBootUiPathFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (BootUiPathNormalizer.DEFAULT_PATH.equals(properties.getPath())
                || !isInternalPath(exchange)
                || isConfiguredApiRequest(exchange)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    private boolean isInternalPath(ServerWebExchange exchange) {
        String path = BootUiReactivePaths.pathWithinApplication(exchange.getRequest());
        return path.equals(BootUiPathNormalizer.DEFAULT_PATH)
                || path.startsWith(BootUiPathNormalizer.DEFAULT_PATH + "/");
    }

    private boolean isConfiguredApiRequest(ServerWebExchange exchange) {
        String path = BootUiReactivePaths.pathWithinApplication(exchange.getRequest());
        String apiPath = properties.getApiPath();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }
}
