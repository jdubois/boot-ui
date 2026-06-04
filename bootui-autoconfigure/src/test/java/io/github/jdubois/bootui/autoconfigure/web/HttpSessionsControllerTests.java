package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.SecretMasker;
import java.util.List;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.session.StandardSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

class HttpSessionsControllerTests {

    @Test
    void sessionsReportIsStableWhenTomcatIsUnavailable() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        HttpSessionsService service = new HttpSessionsService(
                () -> HttpSessionsService.ManagerResolution.unavailable("HTTP Sessions require embedded Tomcat"),
                properties);
        MockMvc mvc = standaloneSetup(new HttpSessionsController(service)).build();

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("HTTP Sessions require embedded Tomcat"))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.valueExposure").value("MASKED"))
                .andExpect(jsonPath("$.sessions").isEmpty());
    }

    @Test
    void sessionsReportListsBoundedSessionsWithMaskedIdsAndAttributes() throws Exception {
        StandardManager manager = new StandardManager();
        addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        addSession(manager, "session-two-abcdef", "apiToken", "secret-token");
        BootUiProperties properties = new BootUiProperties();
        properties.getHttpSessions().setMaxSessions(1);
        MockMvc mvc = standaloneSetup(new HttpSessionsController(new HttpSessionsService(manager, properties)))
                .build();

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalSessions").value(2))
                .andExpect(jsonPath("$.returnedSessions").value(1))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.valueExposure").value("MASKED"))
                .andExpect(jsonPath("$.sessions[0].sessionKey").isNotEmpty())
                .andExpect(jsonPath("$.sessions[0].id").value(SecretMasker.MASKED_VALUE))
                .andExpect(jsonPath("$.sessions[0].idMasked").value(true))
                .andExpect(jsonPath("$.sessions[0].attributes[0].value").value(SecretMasker.MASKED_VALUE))
                .andExpect(jsonPath("$.sessions[0].attributes[0].masked").value(true));
    }

    @Test
    void fullValueExposureShowsSessionIdsAndAttributeValues() throws Exception {
        StandardManager manager = new StandardManager();
        addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(BootUiProperties.ValueExposure.FULL);
        MockMvc mvc = standaloneSetup(new HttpSessionsController(new HttpSessionsService(manager, properties)))
                .build();

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueExposure").value("FULL"))
                .andExpect(jsonPath("$.sessions[0].id").value("session-one-abcdef"))
                .andExpect(jsonPath("$.sessions[0].idMasked").value(false))
                .andExpect(jsonPath("$.sessions[0].attributes[0].value").value("Hello session"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].masked").value(false));
    }

    @Test
    void fullValueExposureCanComeFromRuntimeEnvironment() throws Exception {
        StandardManager manager = new StandardManager();
        addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        BootUiProperties properties = new BootUiProperties();
        MockEnvironment environment = new MockEnvironment();
        MockMvc mvc = standaloneSetup(new HttpSessionsController(new HttpSessionsService(
                        () -> HttpSessionsService.ManagerResolution.available(List.of(manager)),
                        properties,
                        environment)))
                .build();

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueExposure").value("MASKED"))
                .andExpect(jsonPath("$.sessions[0].id").value(SecretMasker.MASKED_VALUE))
                .andExpect(jsonPath("$.sessions[0].attributes[0].masked").value(true));

        environment.setProperty("bootui.expose-values", "FULL");

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueExposure").value("FULL"))
                .andExpect(jsonPath("$.sessions[0].id").value("session-one-abcdef"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].value").value("Hello session"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].masked").value(false));
    }

    @Test
    void metadataOnlyExposureHidesAttributeValuesButKeepsNamesAndTypes() throws Exception {
        StandardManager manager = new StandardManager();
        addSession(manager, "session-one-abcdef", "sampleCount", 42);
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(BootUiProperties.ValueExposure.METADATA_ONLY);
        MockMvc mvc = standaloneSetup(new HttpSessionsController(new HttpSessionsService(manager, properties)))
                .build();

        mvc.perform(get("/bootui/api/http-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueExposure").value("METADATA_ONLY"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].name").value("sampleCount"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].type").value("java.lang.Integer"))
                .andExpect(jsonPath("$.sessions[0].attributes[0].value").doesNotExist())
                .andExpect(jsonPath("$.sessions[0].attributes[0].masked").value(false));
    }

    @Test
    void clearRequiresConfirmation() throws Exception {
        StandardManager manager = new StandardManager();
        StandardSession session = addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        MockMvc mvc = standaloneSetup(
                        new HttpSessionsController(new HttpSessionsService(manager, new BootUiProperties())))
                .build();

        mvc.perform(post(
                                "/bootui/api/http-sessions/{sessionKey}/clear",
                                HttpSessionsService.sessionKey(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("confirmation_required"));
        assertThat(session.getAttribute("sampleMessage")).isEqualTo("Hello session");
    }

    @Test
    void clearIsBlockedWhenPanelIsReadOnly() throws Exception {
        StandardManager manager = new StandardManager();
        StandardSession session = addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        BootUiProperties properties = new BootUiProperties();
        properties.panel("http-sessions").setReadOnly(true);
        MockMvc mvc = standaloneSetup(new HttpSessionsController(new HttpSessionsService(manager, properties)))
                .build();

        mvc.perform(post(
                                "/bootui/api/http-sessions/{sessionKey}/clear",
                                HttpSessionsService.sessionKey(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("read_only"));
        assertThat(session.getAttribute("sampleMessage")).isEqualTo("Hello session");
    }

    @Test
    void clearRemovesSessionAttributesWithoutInvalidatingSession() throws Exception {
        StandardManager manager = new StandardManager();
        StandardSession session = addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        session.setAttribute("sampleCount", 42);
        MockMvc mvc = standaloneSetup(
                        new HttpSessionsController(new HttpSessionsService(manager, new BootUiProperties())))
                .build();

        mvc.perform(post(
                                "/bootui/api/http-sessions/{sessionKey}/clear",
                                HttpSessionsService.sessionKey(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"))
                .andExpect(jsonPath("$.affectedAttributes").value(2));
        assertThat(session.isValid()).isTrue();
        assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
    }

    @Test
    void invalidateDestroysSession() throws Exception {
        StandardManager manager = new StandardManager();
        StandardSession session = addSession(manager, "session-one-abcdef", "sampleMessage", "Hello session");
        MockMvc mvc = standaloneSetup(
                        new HttpSessionsController(new HttpSessionsService(manager, new BootUiProperties())))
                .build();

        mvc.perform(post(
                                "/bootui/api/http-sessions/{sessionKey}/invalidate",
                                HttpSessionsService.sessionKey(session.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("destroyed"));
        assertThat(session.isValid()).isFalse();
    }

    private StandardSession addSession(
            StandardManager manager, String id, String attributeName, Object attributeValue) {
        if (manager.getContext() == null) {
            manager.setContext(new StandardContext());
        }
        StandardSession session = new StandardSession(manager);
        session.setId(id);
        session.setCreationTime(System.currentTimeMillis() - 60_000);
        session.setMaxInactiveInterval(1800);
        session.setValid(true);
        session.setAttribute(attributeName, attributeValue);
        manager.add(session);
        return session;
    }
}
