package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class PanelsControllerTests {

    @Test
    void panelsListsEverySidebarPanel() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels.length()").value(25))
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[6].id").value("config"))
                    .andExpect(jsonPath("$.panels[16].id").value("traces"))
                    .andExpect(jsonPath("$.panels[19].id").value("pentest"))
                    .andExpect(jsonPath("$.panels[20].id").value("vulnerabilities"))
                    .andExpect(jsonPath("$.panels[23].id").value("copilot"))
                    .andExpect(jsonPath("$.panels[24].id").value("claude-code"));
        }
    }

    @Test
    void panelsMarksAlwaysAvailablePanelsAsAvailable() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[0].available").value(true))
                    .andExpect(jsonPath("$.panels[3].id").value("memory"))
                    .andExpect(jsonPath("$.panels[3].available").value(true))
                    .andExpect(jsonPath("$.panels[6].id").value("config"))
                    .andExpect(jsonPath("$.panels[6].available").value(true))
                    .andExpect(jsonPath("$.panels[16].id").value("traces"))
                    .andExpect(jsonPath("$.panels[16].available").value(true))
                    .andExpect(jsonPath("$.panels[18].id").value("http-probe"))
                    .andExpect(jsonPath("$.panels[18].available").value(true))
                    .andExpect(jsonPath("$.panels[19].id").value("pentest"))
                    .andExpect(jsonPath("$.panels[19].available").value(true))
                    .andExpect(jsonPath("$.panels[20].id").value("vulnerabilities"))
                    .andExpect(jsonPath("$.panels[20].available").value(true));
        }
    }

    @Test
    void panelsMarksActuatorBackedPanelsUnavailableWhenEndpointsAreAbsent() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[1].id").value("health"))
                    .andExpect(jsonPath("$.panels[1].available").value(false))
                    .andExpect(jsonPath("$.panels[2].id").value("metrics"))
                    .andExpect(jsonPath("$.panels[2].available").value(false))
                    .andExpect(jsonPath("$.panels[4].id").value("startup"))
                    .andExpect(jsonPath("$.panels[4].available").value(false))
                    .andExpect(jsonPath("$.panels[8].id").value("loggers"))
                    .andExpect(jsonPath("$.panels[8].available").value(false))
                    .andExpect(jsonPath("$.panels[9].id").value("beans"))
                    .andExpect(jsonPath("$.panels[9].available").value(false))
                    .andExpect(jsonPath("$.panels[10].id").value("conditions"))
                    .andExpect(jsonPath("$.panels[10].available").value(false))
                    .andExpect(jsonPath("$.panels[11].id").value("mappings"))
                    .andExpect(jsonPath("$.panels[11].available").value(false));
        }
    }

    @Test
    void panelsMarksTelemetryPanelsUnavailableWhenTelemetryIsDisabled() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.getTelemetry().setEnabled(false);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[15].id").value("ai"))
                    .andExpect(jsonPath("$.panels[15].available").value(false))
                    .andExpect(jsonPath("$.panels[15].unavailableReason").value("Telemetry receiver is disabled"))
                    .andExpect(jsonPath("$.panels[16].id").value("traces"))
                    .andExpect(jsonPath("$.panels[16].available").value(false))
                    .andExpect(jsonPath("$.panels[16].unavailableReason").value("Telemetry receiver is disabled"));
        }
    }

    @Test
    void panelsMarksAiUnavailableWithoutSpringAi() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[15].id").value("ai"))
                    .andExpect(jsonPath("$.panels[15].available").value(false))
                    .andExpect(jsonPath("$.panels[15].unavailableReason")
                            .value("Spring AI ChatClient is not on the classpath"));
        }
    }

    @Test
    void panelsExposeEnabledAndReadOnlyState() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.panel("config").setEnabled(false);
            properties.panel("loggers").setReadOnly(true);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[6].id").value("config"))
                    .andExpect(jsonPath("$.panels[6].enabled").value(false))
                    .andExpect(jsonPath("$.panels[6].available").value(true))
                    .andExpect(jsonPath("$.panels[6].readOnly").value(false))
                    .andExpect(jsonPath("$.panels[8].id").value("loggers"))
                    .andExpect(jsonPath("$.panels[8].enabled").value(true))
                    .andExpect(jsonPath("$.panels[8].readOnly").value(true))
                    .andExpect(jsonPath("$.panels[8].readOnlyReason")
                            .value("Panel is read-only via bootui.panels.loggers.read-only=true"));
        }
    }

    @Test
    void panelsApplyGlobalReadOnlyOnlyToActionCapablePanels() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.setReadOnly(true);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[0].readOnly").value(false))
                    .andExpect(jsonPath("$.panels[6].id").value("config"))
                    .andExpect(jsonPath("$.panels[6].readOnly").value(true))
                    .andExpect(jsonPath("$.panels[6].readOnlyReason")
                            .value("BootUI is read-only via bootui.read-only=true"));
        }
    }
}
