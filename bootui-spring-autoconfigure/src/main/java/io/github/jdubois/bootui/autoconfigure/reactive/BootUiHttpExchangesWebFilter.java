package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.web.BootUiHttpExchangeRepository;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import java.util.Set;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * BootUI's reactive recording filter: Actuator's {@link HttpExchangesWebFilter}, except that it does not record
 * BootUI's own requests into BootUI's own repository while {@code bootui.monitoring.exclude-self} is on, so console
 * polling never takes a slot from application traffic. The servlet sibling is {@code BootUiHttpExchangesFilter}.
 *
 * <p>The decision uses the decoded path below the WebFlux base path, resolved by {@link BootUiReactivePaths} exactly
 * as BootUI's reactive safety filters resolve it, never the query string. When the repository is an application's
 * own, every request is recorded as before and BootUI hides its own at read time.</p>
 */
public class BootUiHttpExchangesWebFilter extends HttpExchangesWebFilter {

    private final boolean skipBootUiRequests;
    private final String path;
    private final String apiPath;

    public BootUiHttpExchangesWebFilter(
            HttpExchangeRepository repository,
            Set<Include> includes,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        super(repository, includes);
        this.skipBootUiRequests = repository instanceof BootUiHttpExchangeRepository bootUiRepository
                && bootUiRepository.ownsRetention()
                && !selfDataFilter.shouldInclude(true);
        this.path = properties.getPath();
        this.apiPath = properties.getApiPath();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (skipBootUiRequests && isBootUiPath(BootUiReactivePaths.pathWithinApplication(exchange.getRequest()))) {
            return chain.filter(exchange);
        }
        return super.filter(exchange, chain);
    }

    private boolean isBootUiPath(String requestPath) {
        return BootUiMounts.contains(requestPath, path, apiPath);
    }
}
