package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.core.dto.PanelDto;
import java.util.List;
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

            int size = io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels.all()
                    .size();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels.length()").value(size))
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[" + (size - 1) + "].id").value("graalvm"));
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
                    .andExpect(jsonPath("$.panels[4].id").value("tuning-advisor"))
                    .andExpect(jsonPath("$.panels[4].available").value(true))
                    .andExpect(jsonPath("$.panels[7].id").value("config"))
                    .andExpect(jsonPath("$.panels[7].available").value(true))
                    .andExpect(jsonPath("$.panels[18].id").value("traces"))
                    .andExpect(jsonPath("$.panels[18].available").value(true))
                    .andExpect(jsonPath("$.panels[20].id").value("http-probe"))
                    .andExpect(jsonPath("$.panels[20].available").value(true))
                    .andExpect(jsonPath("$.panels[21].id").value("pentest"))
                    .andExpect(jsonPath("$.panels[21].available").value(true))
                    .andExpect(jsonPath("$.panels[22].id").value("vulnerabilities"))
                    .andExpect(jsonPath("$.panels[22].available").value(true))
                    .andExpect(jsonPath("$.panels[23].id").value("heap-dump"))
                    .andExpect(jsonPath("$.panels[23].available").value(true));
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
                    .andExpect(jsonPath("$.panels[5].id").value("startup"))
                    .andExpect(jsonPath("$.panels[5].available").value(false))
                    .andExpect(jsonPath("$.panels[9].id").value("loggers"))
                    .andExpect(jsonPath("$.panels[9].available").value(false))
                    .andExpect(jsonPath("$.panels[10].id").value("beans"))
                    .andExpect(jsonPath("$.panels[10].available").value(false))
                    .andExpect(jsonPath("$.panels[11].id").value("conditions"))
                    .andExpect(jsonPath("$.panels[11].available").value(false))
                    .andExpect(jsonPath("$.panels[12].id").value("mappings"))
                    .andExpect(jsonPath("$.panels[12].available").value(false))
                    .andExpect(jsonPath("$.panels[13].id").value("database-connection-pools"))
                    .andExpect(jsonPath("$.panels[13].available").value(false))
                    .andExpect(jsonPath("$.panels[13].unavailableReason")
                            .value("No database connection pool beans are available"));
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
                    .andExpect(jsonPath("$.panels[17].id").value("ai"))
                    .andExpect(jsonPath("$.panels[17].available").value(false))
                    .andExpect(jsonPath("$.panels[17].unavailableReason").value("Telemetry receiver is disabled"))
                    .andExpect(jsonPath("$.panels[18].id").value("traces"))
                    .andExpect(jsonPath("$.panels[18].available").value(false))
                    .andExpect(jsonPath("$.panels[18].unavailableReason").value("Telemetry receiver is disabled"));
        }
    }

    @Test
    void panelsMarksAiUnavailableWithoutAiFramework() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[17].id").value("ai"))
                    .andExpect(jsonPath("$.panels[17].available").value(false))
                    .andExpect(jsonPath("$.panels[17].unavailableReason")
                            .value("Spring AI or LangChain4j is not on the classpath"));
        }
    }

    @Test
    void panelsExposeEnabledAndReadOnlyState() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.panel("config").setEnabled(false);
            properties.panel("loggers").setReadOnly(true);
            properties.panel("pentest").setReadOnly(true);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[7].id").value("config"))
                    .andExpect(jsonPath("$.panels[7].enabled").value(false))
                    .andExpect(jsonPath("$.panels[7].available").value(true))
                    .andExpect(jsonPath("$.panels[7].readOnly").value(false))
                    .andExpect(jsonPath("$.panels[9].id").value("loggers"))
                    .andExpect(jsonPath("$.panels[9].enabled").value(true))
                    .andExpect(jsonPath("$.panels[9].readOnly").value(true))
                    .andExpect(jsonPath("$.panels[9].readOnlyReason")
                            .value("Panel is read-only via bootui.panels.loggers.read-only=true"))
                    .andExpect(jsonPath("$.panels[21].id").value("pentest"))
                    .andExpect(jsonPath("$.panels[21].enabled").value(true))
                    .andExpect(jsonPath("$.panels[21].readOnly").value(true))
                    .andExpect(jsonPath("$.panels[21].readOnlyReason")
                            .value("Panel is read-only via bootui.panels.pentest.read-only=true"));
        }
    }

    @Test
    void panelsApplyGlobalReadOnlyToEveryActionCapablePanel() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.setReadOnly(true);
            PanelsController controller = new PanelsController(context, context.getEnvironment(), properties);

            List<String> expectedReadOnlyPanelIds = BootUiPanels.all().stream()
                    .filter(BootUiPanels.Panel::actionCapable)
                    .map(BootUiPanels.Panel::id)
                    .toList();
            List<PanelDto> panels = controller.panels().panels();
            List<String> actualReadOnlyPanelIds =
                    panels.stream().filter(PanelDto::readOnly).map(PanelDto::id).toList();

            assertThat(actualReadOnlyPanelIds).containsExactlyElementsOf(expectedReadOnlyPanelIds);
            assertThat(panels)
                    .filteredOn(PanelDto::readOnly)
                    .extracting(PanelDto::readOnlyReason)
                    .containsOnly("BootUI is read-only via bootui.read-only=true");
        }
    }
}
