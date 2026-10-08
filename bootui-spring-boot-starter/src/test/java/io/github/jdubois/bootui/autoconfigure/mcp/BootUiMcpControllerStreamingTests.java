package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** Spring MVC: when a progress call may stream, and when it falls back to one JSON response. */
class BootUiMcpControllerStreamingTests {

    private static final String PROGRESS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"architecture_scan\",\"arguments\":{},\"_meta\":"
            + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":\"p\"}}}";

    /** Holds the tool, so the stream is still open when the request is checked. */
    private final CountDownLatch release = new CountDownLatch(1);

    private final MockMvc mvc = mvc(release);

    @Test
    void aProgressCallStreamsWhenTheRequestCanGoAsync() throws Exception {
        try {
            mvc.perform(progressCall()).andExpect(request().asyncStarted());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aProgressCallThatCannotGoAsyncFallsBackToOneJsonResponse() throws Exception {
        release.countDown();
        mvc.perform(progressCall().with(servletRequest -> {
                    // As behind a host filter that is not async-supported: startAsync would throw.
                    servletRequest.setAsyncSupported(false);
                    return servletRequest;
                }))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.structuredContent.findings").isEmpty());
    }

    private static MockHttpServletRequestBuilder progressCall() {
        return post("/bootui/api/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", "architecture_scan")
                .content(PROGRESS_CALL);
    }

    private static MockMvc mvc(CountDownLatch release) {
        BootUiProperties properties = new BootUiProperties();
        McpTool scan = new McpTool(
                "architecture_scan",
                "Run the architecture advisor.",
                McpToolSchema.NONE,
                BootUiPanels.ARCHITECTURE,
                true,
                arguments -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("findings", List.of());
                });
        BootUiMcpService service =
                new BootUiMcpService(new BootUiMcpTools(List.of(scan)), properties, new ObjectMapper(), "1.2.3");
        BootUiMcpController controller =
                new BootUiMcpController(service, new McpServerState(BootUiProperties.Mode.ON), properties);
        return MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("bootui.api-path", "/bootui/api")
                .build();
    }
}
