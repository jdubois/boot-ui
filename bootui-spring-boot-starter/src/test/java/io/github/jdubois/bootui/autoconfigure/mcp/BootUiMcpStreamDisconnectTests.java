package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** Spring MVC: closing a request-scoped MCP stream cancels the call (MCP 2026-07-28). */
@SpringBootTest(
        classes = BootUiMcpStreamDisconnectTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "bootui.mcp.enabled=ON", "spring.main.web-application-type=servlet"})
class BootUiMcpStreamDisconnectTests {

    static final McpStreamDisconnectSupport SUPPORT = new McpStreamDisconnectSupport();

    @LocalServerPort
    private int port;

    @Autowired
    private BootUiMcpService service;

    @Test
    void closingTheStreamCancelsTheCall() throws Exception {
        // Spring MVC only notices a failed write: within two keep-alives.
        SUPPORT.closeAfterFirstEventCancels(
                port, service, Duration.ofMillis(2 * McpStreamingCall.HEARTBEAT_MILLIS + 1000));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        static BeanPostProcessor cancellableArchitectureScan() {
            return SUPPORT.replaceService();
        }
    }
}
