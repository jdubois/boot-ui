package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
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

    public ReactiveBootUiExceptionHandler(ExceptionStore store) {
        this.store = store;
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
                    "web");
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with the application's error handling.
        }
        return Mono.error(ex);
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
