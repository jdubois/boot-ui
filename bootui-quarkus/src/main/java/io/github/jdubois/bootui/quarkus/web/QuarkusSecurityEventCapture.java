package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.correlation.TraceIdSource;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.quarkus.correlation.QuarkusRequestCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.spi.runtime.AuthorizationFailureEvent;
import io.quarkus.security.spi.runtime.AuthorizationSuccessEvent;
import io.quarkus.security.spi.runtime.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures Quarkus CDI security events into the shared {@link SecurityEventBuffer} — the Quarkus analogue
 * of Spring's Actuator {@code AuditEventRepository}, which has no Quarkus counterpart. Quarkus fires these
 * only when {@code quarkus.security.events.enabled=true} (HONEST PARTIAL: authentication success/failure +
 * authorization failure; logout/session events have no Quarkus equivalent). Wired only in dev/test launch
 * modes via the deployment processor, so production stays dark.
 *
 * <p>The observer runs on the request thread (often the Vert.x event loop), so it does only minimal,
 * non-blocking work: it allow-lists the principal name + a small set of safe properties and drops anything
 * credential-shaped at the edge. Bounding, masking and DTO assembly happen later on the read path in the
 * engine {@code SecurityLogsService}. {@code AuthorizationSuccessEvent} is kept out of that buffer — it fires per check
 * and would evict the failures worth reviewing — and recorded, with every authorization failure, as a decision in the
 * runtime journal's {@code authorization} source.</p>
 *
 * <p>When an OpenTelemetry {@link TraceIdSource} is present (capability-gated), the active span's trace
 * id is resolved here too and stamped on the captured event so the Live Activity panel can nest it under
 * the request that produced it — the same correlation mechanism already used for SQL trace and exceptions.
 * {@code Span.current()} still resolves correctly on this observer's request thread because its context
 * propagates via the request's own context storage, not thread identity. The provider is optional: when
 * OpenTelemetry is absent the {@code Instance} is unresolvable and the trace id stays {@code null}, leaving
 * the event uncorrelated (flat) in the feed.</p>
 */
@ApplicationScoped
public class QuarkusSecurityEventCapture {

    private final SecurityEventBuffer buffer;
    private final TraceIdSource traceIdProvider;

    @Inject
    public QuarkusSecurityEventCapture(SecurityEventBuffer buffer, Instance<TraceIdSource> traceIdProvider) {
        this.buffer = buffer;
        this.traceIdProvider = traceIdProvider.isResolvable() ? traceIdProvider.get() : null;
    }

    void onSecurityEvent(@Observes SecurityEvent event) {
        CorrelationContext context = QuarkusRequestCorrelation.current();
        if (event instanceof AuthorizationSuccessEvent || event instanceof AuthorizationFailureEvent) {
            recordDecision(event, context);
        }
        if (event instanceof AuthorizationSuccessEvent) {
            return;
        }
        buffer.record(
                new CapturedSecurityEvent(
                        Instant.now(),
                        principal(event.getSecurityIdentity()),
                        event.getClass().getSimpleName(),
                        data(event),
                        currentTraceId(),
                        context.requestId()),
                context);
    }

    /**
     * Records an authorization decision in the runtime journal's {@code authorization} source
     * ({@code docs/PLAN-v2.md} §5.18): a method's when the event names a secured method, otherwise its request's. Quarkus
     * reports no decision time, and its identity says whether the caller is anonymous.
     */
    private void recordDecision(SecurityEvent event, CorrelationContext context) {
        try {
            boolean granted = event instanceof AuthorizationSuccessEvent;
            Map<String, Object> properties = event.getEventProperties() == null ? Map.of() : event.getEventProperties();
            Object method = properties.get(
                    granted
                            ? AuthorizationSuccessEvent.SECURED_METHOD_KEY
                            : AuthorizationFailureEvent.SECURED_METHOD_KEY);
            Object checker = granted
                    ? properties.get(AuthorizationSuccessEvent.AUTHORIZATION_CONTEXT)
                    : ((AuthorizationFailureEvent) event).getAuthorizationContext();
            SecurityIdentity identity = event.getSecurityIdentity();
            String authentication = identity == null
                    ? AuthorizationPayload.UNKNOWN
                    : identity.isAnonymous() ? AuthorizationPayload.ANONYMOUS : AuthorizationPayload.AUTHENTICATED;
            buffer.recordAuthorization(
                    new AuthorizationPayload(
                            method == null ? AuthorizationPayload.REQUEST : AuthorizationPayload.METHOD,
                            method == null ? null : simpleMethod(method.toString()),
                            checker == null ? null : simpleName(checker.toString()),
                            authentication,
                            granted,
                            identity == null || identity.getRoles() == null
                                    ? 0
                                    : identity.getRoles().size()),
                    System.currentTimeMillis(),
                    context,
                    Thread.currentThread().getName());
        } catch (RuntimeException ex) {
            // Recording never changes the decision it observes.
        }
    }

    /** {@code com.example.OrderService#cancel(...)} as {@code OrderService#cancel}. */
    static String simpleMethod(String description) {
        int hash = description.indexOf('#');
        String owner = hash < 0 ? description : description.substring(0, hash);
        String method = hash < 0 ? "" : description.substring(hash);
        int parameters = method.indexOf('(');
        if (parameters >= 0) {
            method = method.substring(0, parameters);
        }
        return simpleName(owner) + method;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /**
     * The active span's trace id, or {@code null} when OpenTelemetry is absent (no provider) or no span is in
     * context. Fully guarded so capture never disrupts the security check it observes, mirroring
     * {@code QuarkusHttpExchangeCaptureFilter#currentTraceId()}.
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

    private static String principal(SecurityIdentity identity) {
        if (identity == null || identity.getPrincipal() == null) {
            return "anonymous";
        }
        return identity.getPrincipal().getName();
    }

    private static Map<String, Object> data(SecurityEvent event) {
        Map<String, Object> safe = new LinkedHashMap<>();
        Map<String, Object> properties = event.getEventProperties();
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Throwable failure) {
                    safe.put("failure", failure.getClass().getSimpleName());
                } else if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
                    safe.put(entry.getKey(), value.toString());
                }
            }
        }
        return safe;
    }
}
