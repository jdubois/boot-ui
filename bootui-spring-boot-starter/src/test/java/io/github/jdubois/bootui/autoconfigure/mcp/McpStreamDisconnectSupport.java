package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.McpStreamDisconnectContract;
import io.github.jdubois.bootui.conformance.McpStreamFrameContract;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Spring wiring for {@link McpStreamDisconnectContract}: the MCP service bean is swapped for one with its tool. */
final class McpStreamDisconnectSupport {

    final McpStreamDisconnectContract contract = new McpStreamDisconnectContract();
    final McpStreamFrameContract frames = new McpStreamFrameContract();

    BeanPostProcessor replaceService() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof BootUiMcpService)) {
                    return bean;
                }
                return new BootUiMcpService(
                        List.of(contract.tool(), frames.tool()),
                        new BootUiProperties(),
                        new ObjectMapper(),
                        "test",
                        (operation, failure) -> {
                            throw new AssertionError("A cancelled call is not a server fault: " + operation, failure);
                        });
            }
        };
    }

    void closeAfterFirstEventCancels(int port, BootUiMcpService service, Duration noticedWithin) throws Exception {
        contract.closeAfterFirstEventCancels(port, "/bootui/api/mcp", service.dispatcher(), noticedWithin);
    }

    void legacyCloseRunsOnUntilNotificationsCancelled(int port, BootUiMcpService service) throws Exception {
        contract.legacyCloseRunsOnUntilNotificationsCancelled(
                port,
                "/bootui/api/mcp",
                () -> service.dispatcher().runtimeStats().snapshot());
    }

    void legacyBlockingCallIsCancelledByNotification(int port, BootUiMcpService service) throws Exception {
        contract.legacyBlockingCallIsCancelledByNotification(
                port,
                "/bootui/api/mcp",
                () -> service.dispatcher().runtimeStats().snapshot());
    }

    void assertFrames(int port) throws Exception {
        frames.assertFrames(port, "/bootui/api/mcp", "test");
    }
}
