package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class PanelsControllerTests {

    @Test
    void panelsListsEverySidebarPanel() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment())).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels.length()").value(20))
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[19].id").value("vulnerabilities"));
        }
    }

    @Test
    void panelsMarksAlwaysAvailablePanelsAsAvailable() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment())).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[0].id").value("overview"))
                    .andExpect(jsonPath("$.panels[0].available").value(true))
                    .andExpect(jsonPath("$.panels[2].id").value("memory"))
                    .andExpect(jsonPath("$.panels[2].available").value(true))
                    .andExpect(jsonPath("$.panels[8].id").value("config"))
                    .andExpect(jsonPath("$.panels[8].available").value(true))
                    .andExpect(jsonPath("$.panels[12].id").value("http-probe"))
                    .andExpect(jsonPath("$.panels[12].available").value(true))
                    .andExpect(jsonPath("$.panels[19].id").value("vulnerabilities"))
                    .andExpect(jsonPath("$.panels[19].available").value(true));
        }
    }

    @Test
    void panelsMarksActuatorBackedPanelsUnavailableWhenEndpointsAreAbsent() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment())).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[1].id").value("startup"))
                    .andExpect(jsonPath("$.panels[1].available").value(false))
                    .andExpect(jsonPath("$.panels[3].id").value("health"))
                    .andExpect(jsonPath("$.panels[3].available").value(false))
                    .andExpect(jsonPath("$.panels[4].id").value("metrics"))
                    .andExpect(jsonPath("$.panels[4].available").value(false))
                    .andExpect(jsonPath("$.panels[5].id").value("conditions"))
                    .andExpect(jsonPath("$.panels[5].available").value(false))
                    .andExpect(jsonPath("$.panels[6].id").value("beans"))
                    .andExpect(jsonPath("$.panels[6].available").value(false))
                    .andExpect(jsonPath("$.panels[7].id").value("mappings"))
                    .andExpect(jsonPath("$.panels[7].available").value(false))
                    .andExpect(jsonPath("$.panels[10].id").value("loggers"))
                    .andExpect(jsonPath("$.panels[10].available").value(false));
        }
    }
}
