package io.github.jdubois.bootui.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityLogsServiceTests {

    private final SecurityLogsService service = new SecurityLogsService();

    private static CapturedSecurityEvent event(String principal, String type, String iso) {
        return new CapturedSecurityEvent(Instant.parse(iso), principal, type, Map.of(), null);
    }

    @Test
    void clampsMaxLogsIntoSafeRange() {
        assertThat(service.maxLogs(0)).isEqualTo(1);
        assertThat(service.maxLogs(500)).isEqualTo(500);
        assertThat(service.maxLogs(999_999)).isEqualTo(10_000);
    }

    @Test
    void sortsNewestFirstSummarizesAndPages() {
        List<CapturedSecurityEvent> events = List.of(
                event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"),
                event("bob", "AUTHENTICATION_FAILURE", "2026-06-03T08:01:00Z"),
                event("carol", "AUTHENTICATION_SUCCESS", "2026-06-03T08:02:00Z"));

        SecurityLogsReport report =
                service.report(events, 500, true, ValueExposure.MASKED, null, null, null, null, null);

        assertThat(report.auditEventsPresent()).isTrue();
        assertThat(report.maxLogs()).isEqualTo(500);
        assertThat(report.events()).extracting(SecurityLogEventDto::principal).containsExactly("carol", "bob", "alice");
        assertThat(report.typeSummaries())
                .extracting("type")
                .containsExactly("AUTHENTICATION_SUCCESS", "AUTHENTICATION_FAILURE");
        assertThat(report.typeSummaries().get(0).count()).isEqualTo(2);
    }

    @Test
    void masksSensitiveDataEntries() {
        CapturedSecurityEvent secret = new CapturedSecurityEvent(
                Instant.parse("2026-06-03T08:00:00Z"),
                "alice",
                "LOGIN",
                Map.of("password", "hunter2", "user", "alice"),
                null);

        SecurityLogsReport report =
                service.report(List.of(secret), 500, true, ValueExposure.MASKED, null, null, null, null, null);

        assertThat(report.events().get(0).data()).anySatisfy(d -> {
            assertThat(d.name()).isEqualTo("password");
            assertThat(d.masked()).isTrue();
            assertThat(d.value()).isEqualTo("******");
        });
    }

    @Test
    void passesTraceIdThroughToTheDto() {
        CapturedSecurityEvent withTrace =
                new CapturedSecurityEvent(Instant.parse("2026-06-03T08:00:00Z"), "alice", "LOGIN", Map.of(), "trace-1");
        CapturedSecurityEvent withoutTrace =
                new CapturedSecurityEvent(Instant.parse("2026-06-03T08:01:00Z"), "bob", "LOGIN", Map.of(), null);

        SecurityLogsReport report = service.report(
                List.of(withTrace, withoutTrace), 500, true, ValueExposure.MASKED, null, null, null, null, null);

        assertThat(report.events()).extracting(SecurityLogEventDto::traceId).containsExactly(null, "trace-1");
    }

    @Test
    void filtersByPrincipalTypeAndAfter() {
        List<CapturedSecurityEvent> events =
                List.of(event("alice", "OK", "2026-06-03T08:00:00Z"), event("bob", "FAIL", "2026-06-03T09:00:00Z"));

        SecurityLogsReport byPrincipal =
                service.report(events, 500, true, ValueExposure.MASKED, "alice", null, null, null, null);
        assertThat(byPrincipal.events()).hasSize(1);
        assertThat(byPrincipal.events().get(0).principal()).isEqualTo("alice");

        SecurityLogsReport after = service.report(
                events, 500, true, ValueExposure.MASKED, null, null, Instant.parse("2026-06-03T08:30:00Z"), null, null);
        assertThat(after.events()).extracting(SecurityLogEventDto::principal).containsExactly("bob");
    }
}
