package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class BootUiMcpAutoConfigurationTests {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class));

    private WebApplicationContextRunner webMvcRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class));
    }

    @Test
    void mcpBeansAreNotRegisteredByDefault() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).doesNotHaveBean(BootUiMcpController.class);
            assertThat(context).doesNotHaveBean(BootUiMcpService.class);
            assertThat(context).doesNotHaveBean(BootUiMcpTools.class);
        });
    }

    @Test
    void mcpBeansAreRegisteredWhenEnabled() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(BootUiMcpController.class);
            assertThat(context).hasSingleBean(BootUiMcpService.class);
            assertThat(context.getBean(BootUiMcpTools.class).tools()).isNotEmpty();
        });
    }

    @Test
    void statusEndpointReportsTools() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    mvc.perform(get("/bootui/api/mcp"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.server").value("bootui"))
                            .andExpect(jsonPath("$.tools").isArray());
                });
    }

    @Test
    void initializeHandshakeOverHttp() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    String body =
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
                    mvc.perform(post("/bootui/api/mcp")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.result.serverInfo.name").value("bootui"));
                });
    }

    @Test
    void toolsListOverHttp() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
                    mvc.perform(post("/bootui/api/mcp")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.result.tools[0].name").value("architecture_scan"));
                });
    }
}
