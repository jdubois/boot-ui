package io.github.jdubois.bootui.quarkus.sample.security;

import io.quarkus.security.spi.runtime.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Logs Quarkus security events so the Security Logs panel has authentication/authorization activity to
 * show. Quarkus fires these when {@code quarkus.security.events.enabled=true} (set in application.properties).
 * This is the Quarkus analogue of the Spring sample seeding Actuator {@code AuditEvent}s.
 */
@ApplicationScoped
public class SampleSecurityEvents {

    private static final Logger LOG = Logger.getLogger(SampleSecurityEvents.class);

    void onSecurityEvent(@Observes SecurityEvent event) {
        String principal = event.getSecurityIdentity() != null
                        && event.getSecurityIdentity().getPrincipal() != null
                ? event.getSecurityIdentity().getPrincipal().getName()
                : "anonymous";
        LOG.infof("Security event: %s principal=%s", event.getClass().getSimpleName(), principal);
    }
}
