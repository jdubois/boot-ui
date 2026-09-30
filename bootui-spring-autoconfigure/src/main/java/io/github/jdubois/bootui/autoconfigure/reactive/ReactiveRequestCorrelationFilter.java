package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive sibling of {@code RequestCorrelationFilter} ({@code docs/PLAN-v2.md} §5.1): gives each application request
 * BootUI's own request id, with or without tracing, and keeps it on the {@link ServerWebExchange}.
 *
 * <p>As an {@link HttpHandlerDecoratorFactory}, it wraps the whole handler, so the id also covers WebFlux's exception
 * handlers and the response commit, where Actuator's {@code HttpExchangesWebFilter} records the exchange of a failed
 * request. The id's {@link CorrelationContext} is current on the thread that assembles the handler and is written into
 * the Reactor context under {@link #CONTEXT_KEY}. With {@code spring.reactor.context-propagation=auto}, which BootUI
 * contributes as an overridable default, Reactor restores it on every thread the request hops to through
 * {@link BootUiCorrelationThreadLocalAccessor}, so blocking JDBC on {@code boundedElastic} and the exchange recorded in
 * {@code beforeCommit} read it from {@link BootUiCorrelation}. Without Micrometer context propagation, or with it
 * limited, only the assembling thread sees it, and other events carry no request id rather than a guessed one.</p>
 *
 * <p>As a WebFilter ordered just after the highest precedence, it publishes the request's context as an exchange
 * attribute, creating it there if no decorator ran. BootUI's own requests are skipped.</p>
 */
public final class ReactiveRequestCorrelationFilter extends AbstractReactiveBootUiFilter
        implements HttpHandlerDecoratorFactory, Ordered {

    /** The Reactor context and {@code ThreadLocalAccessor} key of the request's {@link CorrelationContext}. */
    public static final String CONTEXT_KEY = "bootui.correlation";

    /** The exchange attribute holding the request's {@link CorrelationContext}. */
    public static final String CORRELATION_ATTRIBUTE =
            ReactiveRequestCorrelationFilter.class.getName() + ".correlation";

    public ReactiveRequestCorrelationFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public HttpHandler apply(HttpHandler handler) {
        return (request, response) -> {
            if (isBootUiPath(request)) {
                return handler.handle(request, response);
            }
            return correlated(
                    CorrelationContext.forRequest(RequestIds.next()), () -> handler.handle(request, response));
        };
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return isBootUiPath(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(context -> {
            Object existing = context.getOrDefault(CONTEXT_KEY, null);
            if (existing instanceof CorrelationContext correlation) {
                exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlation);
                try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
                    return chain.filter(exchange);
                }
            }
            CorrelationContext correlation = CorrelationContext.forRequest(RequestIds.next());
            exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlation);
            return correlated(correlation, () -> chain.filter(exchange));
        });
    }

    /** The request's correlation, or {@link CorrelationContext#NONE} when this filter did not see it. */
    public static CorrelationContext correlation(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(CORRELATION_ATTRIBUTE);
        return attribute instanceof CorrelationContext context ? context : CorrelationContext.NONE;
    }

    private static Mono<Void> correlated(CorrelationContext correlation, Supplier<Mono<Void>> work) {
        return Mono.defer(() -> {
                    try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
                        return work.get();
                    }
                })
                .contextWrite(context -> context.put(CONTEXT_KEY, correlation));
    }

    private boolean isBootUiPath(ServerHttpRequest request) {
        return BootUiMounts.contains(pathWithinApplication(request), properties.getPath(), properties.getApiPath());
    }
}
