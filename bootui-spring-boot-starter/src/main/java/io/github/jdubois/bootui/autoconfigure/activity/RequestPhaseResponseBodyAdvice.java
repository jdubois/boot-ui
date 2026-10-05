package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Marks when Spring MVC starts writing a {@code @ResponseBody} ({@code docs/PLAN-v2.md} §5.1): the handler has
 * returned, and serialization, where lazy loading surfaces, begins. Returns every body unchanged, runs first among the
 * advices, and never fails the request.
 */
@ControllerAdvice
public final class RequestPhaseResponseBodyAdvice implements ResponseBodyAdvice<Object>, Ordered {

    private final RequestPhases phases;

    public RequestPhaseResponseBodyAdvice(RequestPhases phases) {
        this.phases = phases;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        AgentCodePaths.phase(RequestPhase.RESPONSE);
        try {
            phases.mark(BootUiCorrelation.current().requestId(), RequestPhase.RESPONSE);
        } catch (RuntimeException ex) {
            // Phase markers are diagnostics only; the body is written untouched.
        }
        return body;
    }
}
