package io.github.jdubois.bootui.autoconfigure.diagnostics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Captures the trace and HTTP-request context for each Spring Boot audit (security) event as it is
 * published, recording it into a {@link SecurityAuditTraceStore}.
 *
 * <p>Spring publishes {@link AuditApplicationEvent}s synchronously on the thread that produced the
 * event — for web security events that is the request thread, inside the Spring Security filter
 * chain. The active {@code traceId} (if tracing is on) is therefore in the SLF4J MDC and the current
 * {@link HttpServletRequest} is available from {@link RequestContextHolder}, so this listener can
 * link the event to its originating request and trace. When no tracing is active or no request is
 * bound (the event was produced off a request thread), the captured fields are simply {@code null}
 * and the dashboard degrades to its time-based heuristics.
 */
public class SecurityAuditTraceListener implements ApplicationListener<AuditApplicationEvent> {

    private final SecurityAuditTraceStore store;

    public SecurityAuditTraceListener(SecurityAuditTraceStore store) {
        this.store = store;
    }

    @Override
    public void onApplicationEvent(AuditApplicationEvent event) {
        AuditEvent auditEvent = event.getAuditEvent();
        if (auditEvent == null) {
            return;
        }
        String method = null;
        String path = null;
        ServletRequestAttributes attributes = currentServletRequest();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            method = request.getMethod();
            path = request.getRequestURI();
        }
        SecurityAuditTraceStore.Captured captured =
                new SecurityAuditTraceStore.Captured(TraceContext.currentTraceId(), method, path);
        String timestamp = auditEvent.getTimestamp() == null
                ? null
                : auditEvent.getTimestamp().toString();
        store.record(timestamp, auditEvent.getType(), auditEvent.getPrincipal(), captured);
    }

    private static ServletRequestAttributes currentServletRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet ? servlet : null;
    }
}
