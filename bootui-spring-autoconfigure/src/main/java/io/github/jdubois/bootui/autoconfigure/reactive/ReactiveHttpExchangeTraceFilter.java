package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code RequestCorrelationFilter}: instead of the serving thread - which
 * WebFlux has no per-request invariant for - it captures the distributed-trace id active when the
 * request completes, via {@link TraceIdProvider}, feeding {@link HttpExchangeTraceRegistry} so
 * {@code HttpExchangesController} can stamp it onto the exchange it separately captures through Spring
 * Boot's own {@code HttpExchangesWebFilter}.
 *
 * <p>Reads {@link TraceIdProvider#currentTraceId()} from {@code doFinally}, the same relative point in
 * request processing that {@code SqlTraceRecorder}/{@code ExceptionStore} already read it from for
 * SQL/exception capture, so this has the same reliability characteristics (dependent on the
 * application's OpenTelemetry/Reactor context propagation setup), not a new or weaker guarantee.</p>
 *
 * <p><strong>Ordered last</strong> ({@link Ordered#LOWEST_PRECEDENCE}), exactly like {@code
 * ReactiveActivitySignalFilter}: WebFlux's filter chain unwinds completion signals from the innermost
 * filter outward, so a filter registered here - closest to the actual handler - has its {@code
 * doFinally} fire before any more-outer filter's own completion hook (in practice, the tracing
 * instrumentation that ends the active span). Registering any earlier would risk reading {@code
 * Span.current()} after that span has already been closed.</p>
 *
 * <p>Records the request's raw {@link java.net.URI} path (not {@link ServerHttpRequest#getPath()}'s
 * context-relative form) to stay consistent with how {@code HttpExchangesController} reads the path
 * back from Actuator's captured {@code HttpExchange.Request#getUri()} - both sides must compute the same
 * key for {@link HttpExchangeTraceRegistry#match} to find its entry. The inherited {@link
 * #isBootUiRequest} check (used only to decide whether to skip capture) uses the context-relative path
 * as usual, exactly like the other BootUI filters.</p>
 */
public final class ReactiveHttpExchangeTraceFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private final HttpExchangeTraceRegistry registry;
    private final TraceIdProvider traceIdProvider;

    public ReactiveHttpExchangeTraceFilter(
            BootUiProperties properties, HttpExchangeTraceRegistry registry, TraceIdProvider traceIdProvider) {
        super(properties);
        this.registry = registry;
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return isBootUiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();
        String method = request.getMethod() == null ? null : request.getMethod().name();
        String path = request.getURI() == null ? null : request.getURI().getPath();
        return chain.filter(exchange).doFinally(signal -> {
            String traceId = safeCurrentTraceId();
            registry.record(new HttpExchangeTrace(start, System.currentTimeMillis(), method, path, traceId));
        });
    }

    private String safeCurrentTraceId() {
        try {
            return traceIdProvider.currentTraceId();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
