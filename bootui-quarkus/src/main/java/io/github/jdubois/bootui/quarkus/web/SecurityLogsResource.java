package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import org.eclipse.microprofile.config.Config;

/**
 * JAX-RS resource for the Security Logs panel ({@code GET /bootui/api/security-logs}). The Quarkus analogue
 * of the Spring adapter's {@code SecurityLogsController}: a thin transport adapter over the shared engine
 * {@link SecurityLogsService}, which owns type summary, masking, bounding, cap and paging. The capture
 * source is the Quarkus-only {@link SecurityEventBuffer} fed by {@link QuarkusSecurityEventCapture} (Spring
 * keeps Actuator's repository), so the wire is identical.
 *
 * <p>Honestly partial: Quarkus emits CDI security events only when {@code quarkus.security.events.enabled=true},
 * and only for authentication success/failure and authorization failure (no logout/session events). When
 * events are disabled the panel reports unavailable with a clear reason; otherwise it reports the captured
 * events, empty until traffic occurs. Read-only — no state-changing endpoints, hence no write gate.</p>
 */
@Path("/bootui/api/security-logs")
public class SecurityLogsResource {

    private final SecurityEventBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final Config config;
    private final SecurityLogsService service = new SecurityLogsService();

    @Inject
    public SecurityLogsResource(SecurityEventBuffer buffer, QuarkusExposurePolicy exposure, Config config) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SecurityLogsReport logs(
            @QueryParam("principal") String principal,
            @QueryParam("type") String type,
            @QueryParam("after") String after,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        int maxLogs = service.maxLogs(config.getOptionalValue("bootui.security-logs.max-logs", Integer.class)
                .orElse(500));
        if (!eventsEnabled()) {
            return SecurityLogsReport.unavailable(
                    "Quarkus security events are disabled. Set quarkus.security.events.enabled=true to capture"
                            + " authentication/authorization events.",
                    maxLogs);
        }
        return service.report(
                buffer.snapshot(),
                maxLogs,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                blankToNull(principal),
                blankToNull(type),
                parseAfter(after),
                offset,
                limit);
    }

    private boolean eventsEnabled() {
        return config.getOptionalValue("quarkus.security.events.enabled", Boolean.class)
                .orElse(Boolean.FALSE);
    }

    private static Instant parseAfter(String after) {
        String value = blankToNull(after);
        return value == null ? null : Instant.parse(value);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
