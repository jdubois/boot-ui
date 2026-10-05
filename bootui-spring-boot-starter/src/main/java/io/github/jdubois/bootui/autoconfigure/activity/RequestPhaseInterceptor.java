package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
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
        pushRequestValues(request);
        return true;
    }

    /**
     * Hands the request's query and path parameter values to the BootUI agent's request value holder
     * ({@code docs/PLAN-v2.md} §5.16, M5-6b), only while request-value matching is on: query values are BootUI's own
     * decoding of {@code getQueryString()}, so no {@code getParameter*} call ever reads a body, and path variables are
     * the ones the handler mapping published. Only on the request's first dispatch: {@link RequestCorrelationFilter}
     * removes them where the response completes. Observes only, and never fails the request.
     */
    static void pushRequestValues(HttpServletRequest request) {
        try {
            if (request.getDispatcherType() != DispatcherType.REQUEST || !AgentRequestValues.active()) {
                return;
            }
            CorrelationContext correlation = BootUiCorrelation.current();
            if (correlation.bootUi() || correlation.requestId() == null) {
                return;
            }
            AgentRequestValues.Values values = new AgentRequestValues.Values()
                    .addSingle((Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                    .addQuery(request.getQueryString());
            if (!values.isEmpty()) {
                AgentRequestValues.begin(correlation.requestId(), values);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Request-value matching is diagnostics only; the request continues untouched.
        }
    }

    @Override
    public void postHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        mark(RequestPhase.RESPONSE);
    }

    private void mark(RequestPhase phase) {
        // Where the BootUI agent's code-paths nodes record the phase they entered in (docs/PLAN-v2.md M5-4a).
        AgentCodePaths.phase(phase);
        try {
            phases.mark(BootUiCorrelation.current().requestId(), phase);
        } catch (RuntimeException ex) {
            // Phase markers are diagnostics only; the request continues untouched.
        }
    }
}
