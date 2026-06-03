package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.test.web.servlet.MockMvc;

class SecurityLogsControllerTests {

    @Test
    void absentAuditRepositoryReturnsUnavailableReport() throws Exception {
        MockMvc mvc = buildMvc(null, new BootUiProperties());

        mvc.perform(get("/bootui/api/security-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditEventsPresent").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No AuditEventRepository bean is available"))
                .andExpect(jsonPath("$.maxLogs").value(500))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void eventsAreNewestFirstAndBoundedByConfiguredMaximum() throws Exception {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(event("alice", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"));
        repository.add(event("bob", "AUTHORIZATION_DENIED", "2026-06-03T08:01:00Z"));
        repository.add(event("carol", "AUTHENTICATION_FAILURE", "2026-06-03T08:02:00Z"));
        BootUiProperties properties = new BootUiProperties();
        properties.getSecurityLogs().setMaxLogs(2);

        MockMvc mvc = buildMvc(repository, properties);

        mvc.perform(get("/bootui/api/security-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxLogs").value(2))
                .andExpect(jsonPath("$.page.total").value(2))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].principal").value("carol"))
                .andExpect(jsonPath("$.events[1].principal").value("bob"));
    }

    @Test
    void filtersByPrincipalTypeAndAfter() throws Exception {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(event("developer", "AUTHENTICATION_SUCCESS", "2026-06-03T08:00:00Z"));
        repository.add(event("developer", "AUTHENTICATION_FAILURE", "2026-06-03T08:05:00Z"));
        repository.add(event("admin", "AUTHENTICATION_FAILURE", "2026-06-03T08:10:00Z"));

        MockMvc mvc = buildMvc(repository, new BootUiProperties());

        mvc.perform(get("/bootui/api/security-logs")
                        .param("principal", "developer")
                        .param("type", "AUTHENTICATION_FAILURE")
                        .param("after", "2026-06-03T08:01:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].principal").value("developer"))
                .andExpect(jsonPath("$.events[0].type").value("AUTHENTICATION_FAILURE"));
    }

    @Test
    void masksSensitiveEventDataAndHidesValuesInMetadataOnlyMode() throws Exception {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository();
        repository.add(new AuditEvent(
                Instant.parse("2026-06-03T08:00:00Z"),
                "developer",
                "AUTHENTICATION_SUCCESS",
                Map.of("password", "secret", "path", "/api/secure", "sessionId", "abc123")));

        MockMvc maskedMvc = buildMvc(repository, new BootUiProperties());
        maskedMvc
                .perform(get("/bootui/api/security-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].data[0].name").value("password"))
                .andExpect(jsonPath("$.events[0].data[0].value").value("******"))
                .andExpect(jsonPath("$.events[0].data[0].masked").value(true))
                .andExpect(jsonPath("$.events[0].data[1].name").value("path"))
                .andExpect(jsonPath("$.events[0].data[1].value").value("/api/secure"))
                .andExpect(jsonPath("$.events[0].data[2].name").value("sessionId"))
                .andExpect(jsonPath("$.events[0].data[2].value").value("******"));

        BootUiProperties metadataOnly = new BootUiProperties();
        metadataOnly.setExposeValues(ValueExposure.METADATA_ONLY);
        MockMvc hiddenMvc = buildMvc(repository, metadataOnly);
        hiddenMvc
                .perform(get("/bootui/api/security-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].principal").isEmpty())
                .andExpect(jsonPath("$.events[0].data[0].value").isEmpty());
    }

    @Test
    void invalidAfterParameterReturnsBadRequest() throws Exception {
        MockMvc mvc = buildMvc(new InMemoryAuditEventRepository(), new BootUiProperties());

        mvc.perform(get("/bootui/api/security-logs").param("after", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @SuppressWarnings("unchecked")
    private static MockMvc buildMvc(AuditEventRepository repository, BootUiProperties properties) {
        ObjectProvider<AuditEventRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        return standaloneSetup(new SecurityLogsController(provider, properties)).build();
    }

    private static AuditEvent event(String principal, String type, String timestamp) {
        return new AuditEvent(Instant.parse(timestamp), principal, type, Map.of("path", "/api/secure"));
    }
}
