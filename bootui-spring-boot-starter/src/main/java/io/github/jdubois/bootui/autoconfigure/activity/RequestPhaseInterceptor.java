package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.Reader;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
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
        pushRequestValues(request, handler);
        return true;
    }

    /** {@link #pushRequestValues(HttpServletRequest, Object)} without a handler, so no form value is read. */
    static void pushRequestValues(HttpServletRequest request) {
        pushRequestValues(request, null);
    }

    /**
     * Hands the request's query, path, and form parameter values to the BootUI agent's request value holder
     * ({@code docs/PLAN-v2.md} §5.16, M5-6b), only while request-value matching is on: query values are BootUI's own
     * decoding of {@code getQueryString()}, path variables are the ones the handler mapping published, and form values
     * are read only when {@link #formReadable} says Spring reads them as parameters anyway, so a body the handler reads
     * raw stays unread. A request tagged with {@link AgentRequestValues#ASSESSMENT_HEADER} is an assessment request.
     * Only on the request's first dispatch: {@link RequestCorrelationFilter} removes them where the response completes.
     * Observes only, and never fails the request.
     */
    static void pushRequestValues(HttpServletRequest request, Object handler) {
        try {
            if (request.getDispatcherType() != DispatcherType.REQUEST || !AgentRequestValues.active()) {
                return;
            }
            CorrelationContext correlation = BootUiCorrelation.current();
            if (correlation.bootUi() || correlation.requestId() == null) {
                return;
            }
            AgentRequestValues.Values values = new AgentRequestValues.Values()
                    .assessment(request.getHeader(AgentRequestValues.ASSESSMENT_HEADER))
                    .addSingle((Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                    .addQuery(request.getQueryString());
            if (formReadable(request, handler)) {
                values.addForm(request.getParameterMap());
            }
            if (!values.isEmpty() && AgentRequestValues.begin(correlation.requestId(), values)) {
                request.setAttribute(RequestCorrelationFilter.REQUEST_VALUES_ATTRIBUTE, Boolean.TRUE);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Request-value matching is diagnostics only; the request continues untouched.
        }
    }

    /**
     * Whether the request's form values can be read without taking its body from the handler: an
     * {@code application/x-www-form-urlencoded} request to a handler method with no parameter that reads the body raw
     * (an {@code InputStream}, a {@code Reader}, the request itself, or a {@code WebRequest}). Spring binds every other
     * parameter from the request's parameters, rebuilding a {@code @RequestBody} from them for a form.
     */
    static boolean formReadable(HttpServletRequest request, Object handler) {
        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.regionMatches(true, 0, "application/x-www-form-urlencoded", 0, 33)
                || !(handler instanceof HandlerMethod method)) {
            return false;
        }
        for (MethodParameter parameter : method.getMethodParameters()) {
            Class<?> type = parameter.getParameterType();
            if (InputStream.class.isAssignableFrom(type)
                    || Reader.class.isAssignableFrom(type)
                    || ServletRequest.class.isAssignableFrom(type)
                    || WebRequest.class.isAssignableFrom(type)) {
                return false;
            }
        }
        return true;
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
