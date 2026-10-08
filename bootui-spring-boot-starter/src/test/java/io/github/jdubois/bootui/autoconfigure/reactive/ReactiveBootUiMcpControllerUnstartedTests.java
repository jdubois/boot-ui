package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.autoconfigure.mcp.McpTestServices;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** WebFlux: a stream whose exchange is cancelled before its body is subscribed is cancelled, whatever the order. */
class ReactiveBootUiMcpControllerUnstartedTests {

    private static final String PROGRESS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"architecture_scan\",\"arguments\":{},\"_meta\":"
            + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":\"p\"}}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BootUiMcpService service = McpTestServices.withTools(
            List.of(new McpTool(
                    "architecture_scan",
                    "Run the architecture advisor.",
                    McpToolSchema.NONE,
                    BootUiPanels.ARCHITECTURE,
                    true,
                    arguments -> Map.of())),
            objectMapper);

    @Test
    void anExchangeCancelledAfterTheCallIsRegisteredCancelsIt() {
        AtomicReference<Object> unstarted = new AtomicReference<>();
        McpStreamingCall call = streamingCall();
        ReactiveBootUiMcpController.registerUnstarted(unstarted, call);

        ReactiveBootUiMcpController.cancelUnstarted(unstarted);

        assertCancelledAndReleased();
    }

    @Test
    void anExchangeCancelledBeforeTheCallIsRegisteredStillCancelsIt() {
        AtomicReference<Object> unstarted = new AtomicReference<>();
        ReactiveBootUiMcpController.cancelUnstarted(unstarted);

        ReactiveBootUiMcpController.registerUnstarted(unstarted, streamingCall());

        assertCancelledAndReleased();
    }

    private McpStreamingCall streamingCall() {
        BootUiMcpService.Reply reply = service.exchange(
                objectMapper.readTree(PROGRESS_CALL),
                new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/call"), List.of("architecture_scan")),
                true,
                true);
        assertThat(reply.stream())
                .as("a progress call to a progress tool streams")
                .isNotNull();
        assertThat(service.dispatcher().availableCallPermits())
                .isEqualTo(service.dispatcher().maxConcurrentCalls() - 1);
        return reply.stream().call();
    }

    private void assertCancelledAndReleased() {
        assertThat(service.dispatcher().runtimeStats().snapshot().cancellations())
                .isEqualTo(1);
        assertThat(service.dispatcher().availableCallPermits())
                .as("the permit is released at once, not at the execution timeout")
                .isEqualTo(service.dispatcher().maxConcurrentCalls());
    }
}
