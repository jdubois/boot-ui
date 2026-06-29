package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.spi.runtime.AuthorizationSuccessEvent;
import io.quarkus.security.spi.runtime.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
 * engine {@code SecurityLogsService}. {@code AuthorizationSuccessEvent} is dropped — it fires per check and
 * would evict the failures worth reviewing.</p>
 */
@ApplicationScoped
public class QuarkusSecurityEventCapture {

    private final SecurityEventBuffer buffer;

    @Inject
    public QuarkusSecurityEventCapture(SecurityEventBuffer buffer) {
        this.buffer = buffer;
    }

    void onSecurityEvent(@Observes SecurityEvent event) {
        if (event instanceof AuthorizationSuccessEvent) {
            return;
        }
        buffer.record(new CapturedSecurityEvent(
                Instant.now(),
                principal(event.getSecurityIdentity()),
                event.getClass().getSimpleName(),
                data(event)));
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
