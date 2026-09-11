package io.github.jdubois.bootui.autoconfigure.explorer;

import io.github.jdubois.bootui.engine.explorer.LocalInvocationCapture;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Borrows the current sampled server trace; never creates spans or changes host propagation. */
public final class SpringInvocationContext implements InvocationContextProvider {

    private final String requestKey = SpringInvocationContext.class.getName() + "." + System.identityHashCode(this);
    private final LocalInvocationCapture capture;

    public SpringInvocationContext(LocalInvocationCapture capture) {
        this.capture = capture;
    }

    public LocalInvocationCapture capture() {
        return capture;
    }

    LocalInvocationCapture.Request request() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        if (request.getDispatcherType() != DispatcherType.REQUEST || request.isAsyncStarted()) {
            return null;
        }
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid() || !span.isSampled() || span.isRemote()) {
            return null;
        }
        Object existing = request.getAttribute(requestKey);
        if (existing instanceof LocalInvocationCapture.Request state) {
            return state.traceId().equals(span.getTraceId()) ? state : null;
        }
        LocalInvocationCapture.Request state =
                capture.request(span.getTraceId(), span.getSpanId(), true, request.getRequestURI());
        if (state != null) {
            request.setAttribute(requestKey, state);
        }
        return state;
    }

    @Override
    public Context current() {
        Context local = capture.current();
        if (local.traceId() != null) {
            return local;
        }
        SpanContext span = Span.current().getSpanContext();
        return span.isValid() && span.isSampled() ? new Context(span.getTraceId(), span.getSpanId()) : EMPTY;
    }
}
