package io.github.jdubois.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiActivation;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link OverviewController}.
 *
 * <p>These tests exercise the HTTP surface and DTO serialization of the
 * {@code /bootui/api/overview} endpoint without bootstrapping a full Spring
 * Boot context.</p>
 */
class OverviewControllerTests {

    @Test
    void exposesActivationAndEnvironmentMetadata() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "sample-app");
        environment.setProperty("server.port", "8080");
        environment.setProperty("server.servlet.context-path", "/app");
        environment.setActiveProfiles("dev");
        environment.setDefaultProfiles("default");

        BootUiActivation activation = new BootUiActivation(true, "activated by spring.profiles.active=dev", List.of());
        BootUiProperties properties = new BootUiProperties();

        OverviewController controller = new OverviewController(environment, activation, properties);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/bootui/api/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.applicationName").value("sample-app"))
                .andExpect(jsonPath("$.serverPort").value(8080))
                .andExpect(jsonPath("$.contextPath").value("/app"))
                .andExpect(jsonPath("$.activeProfiles", hasItem("dev")))
                .andExpect(jsonPath("$.defaultProfiles", hasItem("default")))
                .andExpect(jsonPath("$.activation.enabled").value(true))
                .andExpect(jsonPath("$.activation.localhostOnly").value(true))
                .andExpect(jsonPath("$.activation.reason").value("activated by spring.profiles.active=dev"))
                .andExpect(jsonPath("$.springBootVersion").exists())
                .andExpect(jsonPath("$.javaVersion").exists())
                .andExpect(jsonPath("$.bootUiVersion").exists());
    }

    @Test
    void reportsLocalhostOnlyFalseWhenNonLocalhostAllowed() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        BootUiActivation activation = new BootUiActivation(true, "forced on", List.of());
        BootUiProperties properties = new BootUiProperties();
        properties.setAllowNonLocalhost(true);

        OverviewController controller = new OverviewController(environment, activation, properties);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/bootui/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activation.localhostOnly").value(false));
    }

    @Test
    void handlesInvalidNumericPropertiesGracefully() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "not-a-number");
        environment.setProperty("management.server.port", "also-bad");
        environment.setProperty("spring.boot.application.startup.time", "nope");

        BootUiActivation activation = new BootUiActivation(true, "ok", List.of());
        BootUiProperties properties = new BootUiProperties();

        OverviewController controller = new OverviewController(environment, activation, properties);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/bootui/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverPort").doesNotExist())
                .andExpect(jsonPath("$.managementPort").doesNotExist())
                .andExpect(jsonPath("$.startupTimeMillis").doesNotExist());
    }
}
