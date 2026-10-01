package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

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

    /** The exchange attribute holding the trace id read while the request's span was still current. */
    public static final String TRACE_ID_ATTRIBUTE = ReactiveHttpExchangeTraceFilter.class.getName() + ".traceId";

    private final HttpExchangeTraceRegistry registry;
    private final TraceIdProvider traceIdProvider;
    private final long requestSlowThresholdMs;

    public ReactiveHttpExchangeTraceFilter(
            BootUiProperties properties, HttpExchangeTraceRegistry registry, TraceIdProvider traceIdProvider) {
        this(properties, registry, traceIdProvider, properties.getActivity().getRequestSlowThresholdMs());
    }

    /**
     * @param requestSlowThresholdMs the slow threshold BootUI's exchange repository applies (see
     *     {@code ExchangeSlowThreshold}), so a trace record is reserved exactly when its exchange is
     */
    public ReactiveHttpExchangeTraceFilter(
            BootUiProperties properties,
            HttpExchangeTraceRegistry registry,
            TraceIdProvider traceIdProvider,
            long requestSlowThresholdMs) {
        super(properties);
        this.registry = registry;
        this.traceIdProvider = traceIdProvider;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
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
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return chain.filter(exchange)
                .doOnError(failure::set)
                .doOnTerminate(() -> {
                    // Before the completion signal travels outward: the outermost correlation filter publishes the
                    // request's journal event once the span has closed, so it reads the trace id from here.
                    String current = safeCurrentTraceId();
                    if (current != null) {
                        exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, current);
                    }
                })
                .doFinally(signal -> {
                    long end = System.currentTimeMillis();
                    String traceId = safeCurrentTraceId();
                    if (traceId == null && exchange.getAttribute(TRACE_ID_ATTRIBUTE) instanceof String captured) {
                        traceId = captured;
                    }
                    registry.record(
                            new HttpExchangeTrace(
                                    start,
                                    end,
                                    method,
                                    path,
                                    traceId,
                                    routeTemplate(exchange),
                                    ReactiveRequestCorrelationFilter.correlation(exchange)
                                            .requestId()),
                            RequestSlowThreshold.isFailedOrSlow(
                                    status(exchange, signal, failure.get()), end - start, requestSlowThresholdMs));
                });
    }

    /**
     * The response status known when the chain completes. WebFlux's exception handlers render an error still
     * propagating after the filters unwind, and Actuator's {@code HttpExchangesWebFilter} records the exchange when
     * that rendered response commits, so the status comes from the error itself: the status an {@link ErrorResponse}
     * such as {@code ResponseStatusException} declares, otherwise {@code 500}. Fully guarded.
     */
    static int status(ServerWebExchange exchange, SignalType signal, Throwable failure) {
        if (signal == SignalType.ON_ERROR) {
            return failure instanceof ErrorResponse errorResponse
                    ? errorResponse.getStatusCode().value()
                    : 500;
        }
        try {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            return status == null ? 0 : status.value();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /**
     * The route pattern WebFlux matched for this request, such as {@code /api/orders/{id}}. The handler
     * mapping publishes it as an exchange attribute while {@code chain.filter} runs, and exchange
     * attributes outlive that call, so reading it from {@code doFinally} sees the matched pattern. Returns
     * {@code null} when nothing matched, so SQL Trace reports the route as unknown rather than inferring
     * one from a path that may embed identifiers. Fully guarded, like the trace-id read beside it.
     */
    static String routeTemplate(ServerWebExchange exchange) {
        try {
            Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null) {
                return null;
            }
            String template =
                    pattern instanceof PathPattern pathPattern ? pathPattern.getPatternString() : pattern.toString();
            template = template == null ? "" : template.trim();
            return template.isEmpty() ? null : template;
        } catch (RuntimeException | NoClassDefFoundError ex) {
            return null;
        }
    }

    private String safeCurrentTraceId() {
        try {
            return traceIdProvider.currentTraceId();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
