package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code AbstractBootUiFilter}: the same BootUI-path matching helpers,
 * adapted from {@code HttpServletRequest} to {@link ServerHttpRequest}.
 *
 * <p>WebFlux has no servlet context path, so path matching uses {@link ServerHttpRequest#getPath()}'s
 * already-context-relative {@code pathWithinApplication()} rather than manually stripping a context
 * path the way the servlet filters strip {@code HttpServletRequest#getContextPath()}.</p>
 *
 * <p>There is also no {@code FilterRegistrationBean} equivalent in WebFlux: a {@link WebFilter} bean is
 * picked up and ordered automatically (via {@code @Order}/{@link org.springframework.core.Ordered}), so
 * every concrete subclass still gates itself with {@link #shouldNotFilter} exactly like its servlet
 * counterpart, rather than relying on a URL-pattern registration.</p>
 */
public abstract class AbstractReactiveBootUiFilter implements WebFilter {

    protected final BootUiProperties properties;

    protected AbstractReactiveBootUiFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public final Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (shouldNotFilter(exchange)) {
            return chain.filter(exchange);
        }
        return doFilterInternal(exchange, chain);
    }

    protected abstract boolean shouldNotFilter(ServerWebExchange exchange);

    protected abstract Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain);

    protected boolean isBootUiApiRequest(ServerHttpRequest request) {
        String path = pathWithinApplication(request);
        String apiPath = properties.getApiPath();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }

    protected boolean isBootUiRequest(ServerHttpRequest request) {
        String path = pathWithinApplication(request);
        String basePath = properties.getPath();
        String apiPath = properties.getApiPath();
        return path.equals(basePath)
                || path.startsWith(basePath + "/")
                || path.equals(apiPath)
                || path.startsWith(apiPath + "/");
    }

    protected String pathWithinApplication(ServerHttpRequest request) {
        return request.getPath().pathWithinApplication().value();
    }

    protected String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Writes a JSON body with the given status, mirroring the servlet filters' direct
     * {@code HttpServletResponse} writes. Callers build their own JSON shape (the two concrete filters
     * intentionally use different shapes, exactly as their servlet counterparts do).
     */
    protected Mono<Void> writeJson(ServerWebExchange exchange, HttpStatusCode status, String json) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
