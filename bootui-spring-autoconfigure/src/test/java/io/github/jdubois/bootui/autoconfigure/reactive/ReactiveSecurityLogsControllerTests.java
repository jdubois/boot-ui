package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;

/**
 * Tests for {@link ReactiveSecurityLogsController}: baseline parity with the servlet {@code
 * SecurityLogsController} over the same {@link AuditEventRepository} (there was previously no dedicated
 * test file for the reactive controller), plus the reactive-only OpenTelemetry trace-id correlation this
 * controller adds - capturing the trace id active when Spring Security publishes each {@link
 * AuditApplicationEvent} into {@link ReactiveSecurityEventTraceRegistry}, then reading it back onto the
 * matching event {@link #logs} returns.
 */
class ReactiveSecurityLogsControllerTests {

    @Test
    void absentAuditRepositoryReturnsUnavailableReport() {
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(emptyProvider(), new BootUiProperties());

        SecurityLogsReport report = controller.logs(null, null, null, null, null);

        assertThat(report.auditEventsPresent()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("No AuditEventRepository bean is available");
    }

    @Test
    void eventsAreNewestFirst() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"));
        repository.add(event("bob", "AUTHORIZATION_DENIED", "2026-06-03T08:01:00Z"));
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());

        SecurityLogsReport report = controller.logs(null, null, null, null, null);

        assertThat(report.events()).hasSize(2);
        assertThat(report.events().get(0).principal()).isEqualTo("bob");
        assertThat(report.events().get(1).principal()).isEqualTo("alice");
    }

    @Test
    void filtersByPrincipalTypeAndAfter() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(event("developer", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"));
        repository.add(event("developer", "AUTHENTICATION_FAILURE", "2026-06-03T08:05:00Z"));
        repository.add(event("admin", "AUTHENTICATION_FAILURE", "2026-06-03T08:10:00Z"));
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());

        SecurityLogsReport report =
                controller.logs("developer", "AUTHENTICATION_FAILURE", "2026-06-03T08:01:00Z", null, null);

        assertThat(report.events()).hasSize(1);
        assertThat(report.events().get(0).principal()).isEqualTo("developer");
        assertThat(report.events().get(0).type()).isEqualTo("AUTHENTICATION_FAILURE");
    }

    @Test
    void leavesTraceIdNullWhenNoRegistryIsInstalled() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"));
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());

        SecurityLogsReport report = controller.logs(null, null, null, null, null);

        assertThat(report.events().get(0).traceId()).isNull();
    }

    @Test
    void onApplicationEventCapturesTheActiveTraceIdForLaterCorrelation() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        AuditEvent event = event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z");
        repository.add(event);
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());
        controller.setTraceIdProvider(() -> "trace-xyz");
        controller.setTraceRegistry(new ReactiveSecurityEventTraceRegistry(10));

        controller.onApplicationEvent(new AuditApplicationEvent(event));
        SecurityLogsReport report = controller.logs(null, null, null, null, null);

        assertThat(report.events().get(0).traceId()).isEqualTo("trace-xyz");
    }

    @Test
    void onApplicationEventNoOpsWhenNoProviderIsInstalled() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        AuditEvent event = event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z");
        repository.add(event);
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());
        controller.setTraceRegistry(new ReactiveSecurityEventTraceRegistry(10));

        controller.onApplicationEvent(new AuditApplicationEvent(event));
        SecurityLogsReport report = controller.logs(null, null, null, null, null);

        assertThat(report.events().get(0).traceId()).isNull();
    }

    @Test
    void onApplicationEventNeverThrowsWhenTheProviderFails() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        AuditEvent event = event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z");
        repository.add(event);
        ReactiveSecurityLogsController controller =
                new ReactiveSecurityLogsController(providerOf(repository), new BootUiProperties());
        controller.setTraceIdProvider(() -> {
            throw new IllegalStateException("no context propagated");
        });
        controller.setTraceRegistry(new ReactiveSecurityEventTraceRegistry(10));

        controller.onApplicationEvent(new AuditApplicationEvent(event));

        assertThat(controller.logs(null, null, null, null, null).events().get(0).traceId())
                .isNull();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuditEventRepository> providerOf(AuditEventRepository repository) {
        ObjectProvider<AuditEventRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuditEventRepository> emptyProvider() {
        ObjectProvider<AuditEventRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static AuditEvent event(String principal, String type, String timestamp) {
        return new AuditEvent(Instant.parse(timestamp), principal, type, Map.of("path", "/api/secure"));
    }
}
