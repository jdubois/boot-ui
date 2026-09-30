package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Marks when Spring MVC enters a request's handler, and when the handler has returned and a view is about to render
 * ({@code docs/PLAN-v2.md} §5.1). A {@code @ResponseBody} is written inside the handler adapter, before
 * {@code postHandle}, so {@link RequestPhaseResponseBodyAdvice} marks that response instead. Observes only, and never
 * fails the request.
 */
public final class RequestPhaseInterceptor implements HandlerInterceptor {

    private final RequestPhases phases;

    public RequestPhaseInterceptor(RequestPhases phases) {
        this.phases = phases;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        mark(RequestPhase.HANDLER);
        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        mark(RequestPhase.RESPONSE);
    }

    private void mark(RequestPhase phase) {
        try {
            phases.mark(BootUiCorrelation.current().requestId(), phase);
        } catch (RuntimeException ex) {
            // Phase markers are diagnostics only; the request continues untouched.
        }
    }
}
