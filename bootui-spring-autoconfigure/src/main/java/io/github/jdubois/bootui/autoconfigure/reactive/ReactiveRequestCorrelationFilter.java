package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

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

    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
    private volatile long requestSlowThresholdMs = RequestSlowThreshold.DEFAULT_MILLIS;
    private volatile RequestPhases phases;

    public ReactiveRequestCorrelationFilter(BootUiProperties properties) {
        super(properties);
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives one {@code HTTP} event per request
     * when its filter chain completes, with the matched route and the status WebFlux will render.
     *
     * @param requestSlowThresholdMs {@code bootui.activity.request-slow-threshold-ms}, which marks a request slow
     * @param phases the phase markers of recent requests, which name a request's operation; {@code null} names none
     */
    public void setRuntimeEventSink(RuntimeEventSink journal, long requestSlowThresholdMs, RequestPhases phases) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
        this.phases = phases;
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
                    return published(exchange, correlation, chain.filter(exchange));
                }
            }
            CorrelationContext correlation = CorrelationContext.forRequest(RequestIds.next());
            exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlation);
            return correlated(correlation, () -> published(exchange, correlation, chain.filter(exchange)));
        });
    }

    /** {@code chain} that publishes the request's {@code HTTP} event to the journal when it completes. */
    private Mono<Void> published(ServerWebExchange exchange, CorrelationContext correlation, Mono<Void> chain) {
        RuntimeEventSink sink = journal;
        if (sink == RuntimeEventSink.NONE) {
            return chain;
        }
        long startNanos = System.nanoTime();
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return chain.doOnError(failure::set).doFinally(signal -> {
            try {
                long durationNanos = System.nanoTime() - startNanos;
                int status = ReactiveHttpExchangeTraceFilter.status(exchange, signal, failure.get());
                if (status == 0 && signal == SignalType.ON_COMPLETE) {
                    // A handler that completes without setting a status renders 200 when the response commits; a
                    // cancelled request keeps 0, as it has no status.
                    status = 200;
                }
                ServerHttpRequest request = exchange.getRequest();
                String requestId = correlation.requestId();
                RequestPhases requestPhases = phases;
                sink.offer(new RuntimeEvent(
                        JournalSource.HTTP,
                        start,
                        durationNanos,
                        requestId,
                        null,
                        correlation.traceId(),
                        null,
                        thread,
                        null,
                        RequestSlowThreshold.isFailedOrSlow(status, durationNanos / 1_000_000, requestSlowThresholdMs),
                        new HttpPayload(
                                request.getMethod() == null
                                        ? null
                                        : request.getMethod().name(),
                                request.getURI() == null
                                        ? null
                                        : request.getURI().getPath(),
                                ReactiveHttpExchangeTraceFilter.routeTemplate(exchange),
                                requestPhases == null ? null : requestPhases.operationOf(requestId),
                                status)));
            } catch (RuntimeException ex) {
                // Publishing never disturbs the response.
            }
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
