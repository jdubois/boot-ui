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

    private static final List<String> PANEL_IDS = List.of(
            BootUiPanels.OVERVIEW,
            BootUiPanels.HEALTH,
            BootUiPanels.METRICS,
            BootUiPanels.MEMORY,
            BootUiPanels.TUNING_ADVISOR,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.THREADS,
            BootUiPanels.STARTUP,
            BootUiPanels.GRAALVM,
            BootUiPanels.CONFIG,
            BootUiPanels.PROFILES,
            BootUiPanels.LOGGERS,
            BootUiPanels.BEANS,
            BootUiPanels.CONDITIONS,
            BootUiPanels.MAPPINGS,
            BootUiPanels.SPRING_SECURITY,
            BootUiPanels.SECURITY_LOGS,
            BootUiPanels.PENTEST,
            BootUiPanels.SCHEDULED,
            BootUiPanels.DATABASE_CONNECTION_POOLS,
            BootUiPanels.DATA,
            BootUiPanels.SPRING_CACHE,
            BootUiPanels.AI,
            BootUiPanels.TRACES,
            BootUiPanels.LOG_TAIL,
            BootUiPanels.HTTP_EXCHANGES,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.ARCHITECTURE,
            BootUiPanels.VULNERABILITIES,
            BootUiPanels.DEVTOOLS,
            BootUiPanels.DEV_SERVICES,
            BootUiPanels.COPILOT,
            BootUiPanels.CLAUDE_CODE);

    @Test
    void panelsListsEverySidebarPanel() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            assertThat(BootUiPanels.all()).extracting(BootUiPanels.Panel::id).containsExactlyElementsOf(PANEL_IDS);
            assertThat(controller.panels().panels()).extracting(PanelDto::id).containsExactlyElementsOf(PANEL_IDS);

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels.length()").value(PANEL_IDS.size()));
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
                    .andExpect(jsonPath(panelPath(BootUiPanels.OVERVIEW) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MEMORY) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TUNING_ADVISOR) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HEAP_DUMP) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.THREADS) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTEST) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_PROBE) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.VULNERABILITIES) + ".available")
                            .value(true));
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
                    .andExpect(jsonPath(panelPath(BootUiPanels.HEALTH) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.METRICS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.STARTUP) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.BEANS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONDITIONS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MAPPINGS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY_LOGS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY_LOGS) + ".unavailableReason")
                            .value("No AuditEventRepository bean is available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.DATABASE_CONNECTION_POOLS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.DATABASE_CONNECTION_POOLS) + ".unavailableReason")
                            .value("No database connection pool beans are available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_EXCHANGES) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_EXCHANGES) + ".unavailableReason")
                            .value("HTTP exchange repository not available"));
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
                    .andExpect(
                            jsonPath(panelPath(BootUiPanels.AI) + ".available").value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.AI) + ".unavailableReason")
                            .value("Telemetry receiver is disabled"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".unavailableReason")
                            .value("Telemetry receiver is disabled"));
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
                    .andExpect(
                            jsonPath(panelPath(BootUiPanels.AI) + ".available").value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.AI) + ".unavailableReason")
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
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".enabled")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".readOnly")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".enabled")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".readOnly")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".readOnlyReason")
                            .value("Panel is read-only via bootui.panels.loggers.read-only=true"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTEST) + ".enabled")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTEST) + ".readOnly")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTEST) + ".readOnlyReason")
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

    private static String panelPath(String id) {
        return "$.panels[" + PANEL_IDS.indexOf(id) + "]";
    }
}
