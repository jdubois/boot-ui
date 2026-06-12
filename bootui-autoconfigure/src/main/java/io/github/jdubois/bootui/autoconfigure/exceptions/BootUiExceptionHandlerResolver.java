package io.github.jdubois.bootui.autoconfigure.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Observe-only {@link HandlerExceptionResolver} that records exceptions thrown by Spring MVC
 * handlers into the {@link ExceptionStore}, together with their request context.
 *
 * <p>It runs at {@link Ordered#HIGHEST_PRECEDENCE} so it sees every handler exception before any
 * real resolver (including {@code @ExceptionHandler} methods) can consume it, and it always returns
 * {@code null} so the actual resolution is left entirely to the rest of the chain. The request path
 * is captured without its query string to avoid surfacing secrets passed as query parameters.</p>
 */
public class BootUiExceptionHandlerResolver implements HandlerExceptionResolver, Ordered {

    private final ExceptionStore store;

    public BootUiExceptionHandlerResolver(ExceptionStore store) {
        this.store = store;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            store.record(
                    ex,
                    Thread.currentThread().getName(),
                    request != null ? request.getMethod() : null,
                    request != null ? request.getRequestURI() : null,
                    describeHandler(handler),
                    "web");
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with the application's error handling.
        }
        return null;
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
