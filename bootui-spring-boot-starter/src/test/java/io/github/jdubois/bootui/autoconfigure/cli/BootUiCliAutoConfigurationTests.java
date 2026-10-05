package io.github.jdubois.bootui.autoconfigure.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.engine.cli.CliService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class BootUiCliAutoConfigurationTests {

    private static final String CLI = "/bootui/api/cli";

    private WebApplicationContextRunner webMvcRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class));
    }

    private static MockMvc mvc(ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .build();
    }

    @Test
    void cliBeansAreRegisteredAndEnabledByDefault() {
        webMvcRunner().withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(CliService.class);
            assertThat(context).hasSingleBean(BootUiCliController.class);
            assertThat(context.getBean(CliService.class).enabled()).isTrue();
        });
    }

    @Test
    void cliCatalogIsServedWithoutEnablingTheMcpServer() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> mvc(context)
                        .perform(get(CLI))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(true))
                        .andExpect(jsonPath("$.serverName").value("bootui"))
                        .andExpect(jsonPath("$.endpoint").value(CLI))
                        .andExpect(jsonPath("$.toolCount").value(Matchers.greaterThan(0)))
                        .andExpect(jsonPath("$.tools[0].name").isString())
                        .andExpect(jsonPath("$.tools[0].panel").isString())
                        .andExpect(jsonPath("$.tools[0].schema").isString()));
    }

    @Test
    void readToolIsInvocable() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> mvc(context)
                        .perform(post(CLI + "/tools/get_overview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isOk()));
    }

    @Test
    void readToolWithNoRequestBodyUsesEmptyArguments() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context ->
                        mvc(context).perform(post(CLI + "/tools/get_overview")).andExpect(status().isOk()));
    }

    @Test
    void unknownToolIsNotFound() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> mvc(context)
                        .perform(post(CLI + "/tools/no_such_tool")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error").value(Matchers.containsString("no_such_tool"))));
    }

    @Test
    void argumentTheToolDoesNotDeclareIsRejected() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> mvc(context)
                        .perform(post(CLI + "/tools/get_overview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"id\":\"anything\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").isString()));
    }

    @Test
    void disabledPanelRefusesItsToolsWithForbidden() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.panels.overview.enabled=false")
                .run(context -> mvc(context)
                        .perform(post(CLI + "/tools/get_overview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isForbidden()));
    }

    @Test
    void globalReadOnlyStillServesReadToolsOverPost() {
        // A read tool is a POST only because arguments travel in a body. Global read-only governs writes, so
        // blocking it here would make the CLI useless in exactly the locked-down setups it is most wanted in.
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.read-only=true")
                .run(context -> mvc(context)
                        .perform(post(CLI + "/tools/get_overview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isOk()));
    }

    @Test
    void readOnlyPanelRefusesItsActionToolsButStillServesItsReads() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.panels.exceptions.read-only=true")
                .run(context -> {
                    MockMvc mvc = mvc(context);
                    mvc.perform(post(CLI + "/tools/clear_exceptions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andExpect(status().isForbidden());
                    mvc.perform(post(CLI + "/tools/get_exceptions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andExpect(status().isOk());
                });
    }

    @Test
    void disabledEndpointAnswersTheCatalogButRefusesInvocation() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.cli.enabled=false")
                .run(context -> {
                    assertThat(context.getBean(CliService.class).enabled()).isFalse();
                    MockMvc mvc = mvc(context);
                    mvc.perform(get(CLI))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.enabled").value(false))
                            .andExpect(jsonPath("$.toolCount").value(0));
                    mvc.perform(post(CLI + "/tools/get_overview")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.error").isString());
                });
    }

    @Test
    void catalogReportsTheConfiguredResultCap() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.cli.max-results=7")
                .run(context -> mvc(context)
                        .perform(get(CLI))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.maxResults").value(7)));
    }

    @Test
    void cliTrafficIsNotCountedAsMcpAgentActivity() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    mvc(context)
                            .perform(post(CLI + "/tools/get_overview")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andExpect(status().isOk());

                    assertThat(context.getBean(CliService.class)
                                    .runtimeStats()
                                    .snapshot()
                                    .callCount())
                            .isEqualTo(1);
                    assertThat(context.getBean(BootUiMcpService.class)
                                    .dispatcher()
                                    .runtimeStats()
                                    .snapshot()
                                    .callCount())
                            .isZero();
                });
    }

    @Test
    void cliEndpointFollowsTheConfiguredApiPath() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.path=/console")
                .run(context -> mvc(context)
                        .perform(get("/console/api/cli"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.endpoint").value("/console/api/cli")));
    }
}
