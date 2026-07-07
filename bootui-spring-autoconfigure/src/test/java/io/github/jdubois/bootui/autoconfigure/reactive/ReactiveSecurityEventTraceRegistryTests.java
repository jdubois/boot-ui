package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityEventTraceRegistry.SecurityEventTrace;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReactiveSecurityEventTraceRegistry}: the reactive adapter's side-buffer that lets
 * {@link ReactiveSecurityLogsController} stamp a captured OpenTelemetry trace id onto a Spring Security
 * {@code AuditEvent}, which carries no trace id of its own. Mirrors {@code
 * SecurityEventCorrelationRegistryTests}' matching conventions, simplified to a direct trace-id-or-null
 * return (WebFlux has no serving thread to classify against).
 */
class ReactiveSecurityEventTraceRegistryTests {

    @Test
    void matchesUniqueEventByTypePrincipalAndTimestamp() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1_000L, "AUTHORIZATION_FAILURE", "admin", "trace-1"));

        assertThat(registry.match("authorization_failure", "admin", 1_010L)).isEqualTo("trace-1");
    }

    @Test
    void returnsNullWhenTypeOrPrincipalDiffer() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1_000L, "AUTHORIZATION_FAILURE", "admin", "trace-1"));

        assertThat(registry.match("AUTHENTICATION_SUCCESS", "admin", 1_000L)).isNull();
        assertThat(registry.match("AUTHORIZATION_FAILURE", "guest", 1_000L)).isNull();
    }

    @Test
    void returnsNullOutsideTheSlackWindow() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1_000L, "AUTHORIZATION_FAILURE", "admin", "trace-1"));

        assertThat(registry.match("AUTHORIZATION_FAILURE", "admin", 5_000L)).isNull();
    }

    @Test
    void returnsNullWhenMultipleCandidatesMatch() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1_000L, "AUTHORIZATION_FAILURE", "admin", "trace-1"));
        registry.record(new SecurityEventTrace(1_010L, "AUTHORIZATION_FAILURE", "admin", "trace-2"));

        assertThat(registry.match("AUTHORIZATION_FAILURE", "admin", 1_000L)).isNull();
    }

    @Test
    void matchesNullPrincipalToNullPrincipalOnly() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1_000L, "SYSTEM_EVENT", null, "trace-1"));

        assertThat(registry.match("SYSTEM_EVENT", null, 1_000L)).isEqualTo("trace-1");
        assertThat(registry.match("SYSTEM_EVENT", "admin", 1_000L)).isNull();
    }

    @Test
    void evictsOldestBeyondCapacity() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(2);
        registry.record(new SecurityEventTrace(1L, "T", "a", "trace-1"));
        registry.record(new SecurityEventTrace(2L, "T", "b", "trace-2"));
        registry.record(new SecurityEventTrace(3L, "T", "c", "trace-3"));

        assertThat(registry.snapshot()).extracting(SecurityEventTrace::traceId).containsExactly("trace-2", "trace-3");
    }

    @Test
    void ignoresRecordsWithNoUsableTraceId() {
        ReactiveSecurityEventTraceRegistry registry = new ReactiveSecurityEventTraceRegistry(10);
        registry.record(new SecurityEventTrace(1L, "T", "a", null));
        registry.record(new SecurityEventTrace(1L, "T", "b", ""));
        registry.record(null);

        assertThat(registry.snapshot()).isEmpty();
    }
}
