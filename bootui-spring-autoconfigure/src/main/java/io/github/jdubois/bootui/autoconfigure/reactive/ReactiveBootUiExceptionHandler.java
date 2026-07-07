package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code BootUiExceptionHandlerResolver}: observes every handler
 * exception and records it into the {@link ExceptionStore}, then always re-propagates it so the
 * application's own error handling (Spring Boot's default error page, or a host
 * {@code @ExceptionHandler} / {@code ErrorWebExceptionHandler}) still runs unchanged.
 *
 * <p>WebFlux has no reactive analog of MVC's {@code HandlerExceptionResolver} chain (where a
 * resolver observes an exception, returns {@code null}, and cedes to the next resolver in the
 * chain - including {@code @ExceptionHandler} methods). The closest extension point,
 * {@link WebExceptionHandler}, is composed the same way: implementations are tried in ascending
 * {@link Ordered} order, and returning {@link Mono#error(Throwable)} - rather than completing -
 * passes the exception on to the next handler. Registering this at
 * {@link Ordered#HIGHEST_PRECEDENCE} reproduces the same "observe first, let something else render
 * the response" contract as the servlet original.
 *
 * <p><strong>Known fidelity gap</strong> (documented in {@code docs/WEBFLUX-SUPPORT.md}): a
 * {@code @RestController}'s own local {@code @ExceptionHandler} method consumes the exception
 * *inside* the WebFlux dispatch pipeline, before it ever reaches a {@link WebExceptionHandler}
 * (which only sees exceptions that escape the entire pipeline unhandled). Under Spring MVC,
 * {@code BootUiExceptionHandlerResolver} captures those too, because it is itself part of the same
 * {@code HandlerExceptionResolver} chain that {@code @ExceptionHandler} methods are resolved from.
 * So under WebFlux, an exception consumed by the host application's own controller-local
 * {@code @ExceptionHandler} method is not captured here - a narrow edge case, not a correctness
 * regression for exceptions that actually propagate to the server's default error handling.
 */
public class ReactiveBootUiExceptionHandler implements WebExceptionHandler, Ordered {

    private final ExceptionStore store;

    private TraceIdProvider traceIdProvider;

    public ReactiveBootUiExceptionHandler(ExceptionStore store) {
        this.store = store;
    }

    /**
     * Installed only by {@code BootUiReactiveAutoConfiguration} once OpenTelemetry is present, so the
     * exception is nested under its owning request in the Live Activity feed exactly like the Quarkus
     * adapter; left {@code null} otherwise, in which case {@link #currentTraceId()} returns {@code null}
     * and {@code store.record} falls back to its existing six-argument, no-trace-id overload.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        try {
            store.record(
                    ex,
                    Thread.currentThread().getName(),
                    exchange.getRequest().getMethod() != null
                            ? exchange.getRequest().getMethod().name()
                            : null,
                    exchange.getRequest().getURI().getRawPath(),
                    describeHandler(exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)),
                    "web",
                    currentTraceId());
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with the application's error handling.
        }
        return Mono.error(ex);
    }

    /**
     * The trace id active for the request currently unwinding this exception, or {@code null} when no
     * provider is installed or it fails to resolve one - fully guarded so a misbehaving tracer never
     * disrupts the application's own error handling.
     */
    private String currentTraceId() {
        if (traceIdProvider == null) {
            return null;
        }
        try {
            return traceIdProvider.currentTraceId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String describeHandler(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "#"
                    + handlerMethod.getMethod().getName();
        }
        return handler == null ? null : handler.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
