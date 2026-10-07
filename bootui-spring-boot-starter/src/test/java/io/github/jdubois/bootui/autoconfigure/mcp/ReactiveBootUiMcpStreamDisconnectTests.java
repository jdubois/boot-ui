package io.github.jdubois.bootui.autoconfigure.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** Spring WebFlux: closing a request-scoped MCP stream cancels the call (MCP 2026-07-28). */
@SpringBootTest(
        classes = ReactiveBootUiMcpStreamDisconnectTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "bootui.mcp.enabled=ON", "spring.main.web-application-type=reactive"})
class ReactiveBootUiMcpStreamDisconnectTests {

    static final McpStreamDisconnectSupport SUPPORT = new McpStreamDisconnectSupport();

    @LocalServerPort
    private int port;

    @Autowired
    private BootUiMcpService service;

    @Test
    void closingTheStreamCancelsTheCall() throws Exception {
        SUPPORT.closeAfterFirstEventCancels(port, service);
    }

    @Test
    void closingALegacyStreamDoesNotCancelButNotificationsCancelledDoes() throws Exception {
        SUPPORT.legacyCloseRunsOnUntilNotificationsCancelled(port, service);
    }

    @Test
    void notificationsCancelledStopsALegacyBlockingCall() throws Exception {
        SUPPORT.legacyBlockingCallIsCancelledByNotification(port, service);
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
