package io.github.jdubois.bootui.engine.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-neutral capture of a single security/audit event, before masking or DTO assembly.
 *
 * <p>Adapters translate their native event (Spring's Actuator {@code AuditEvent}, a Quarkus CDI
 * {@code io.quarkus.security.spi.runtime.SecurityEvent}) into this record and feed it to
 * {@link SecurityLogsService}; the engine owns type summary, masking, bounding, cap and paging so the
 * wire is identical across frameworks. The {@code data} map is raw key/values — the service sorts,
 * bounds, masks and truncates it.
 *
 * <p>{@code traceId} correlates this event to the request that produced it for the Live Activity panel
 * (see {@code LiveActivityAssembler}), the same trace-id mechanism already used for SQL trace and
 * exceptions. Spring has no source for it here (its Live Activity correlation is thread-based, not
 * trace-id-based, and lives entirely in its own {@code LiveActivityService}), so its call site always
 * passes {@code null}; only the Quarkus adapter stamps a real value.
 */
public record CapturedSecurityEvent(
        Instant timestamp, String principal, String type, Map<String, Object> data, String traceId) {

    public CapturedSecurityEvent {
        data = data == null ? Map.of() : new LinkedHashMap<>(data);
    }
}
