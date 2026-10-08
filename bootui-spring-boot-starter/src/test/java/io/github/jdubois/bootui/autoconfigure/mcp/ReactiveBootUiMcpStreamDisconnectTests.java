package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveRequestCorrelationFilter;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Hooks;

/** Spring WebFlux: closing a request-scoped MCP stream cancels the call (MCP 2026-07-28). */
@SpringBootTest(
        classes = ReactiveBootUiMcpStreamDisconnectTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "bootui.mcp.enabled=ON", "spring.main.web-application-type=reactive"})
@DirtiesContext
class ReactiveBootUiMcpStreamDisconnectTests {

    static final McpStreamDisconnectSupport SUPPORT = new McpStreamDisconnectSupport();

    @LocalServerPort
    private int port;

    @Autowired
    private BootUiMcpService service;

    /**
     * The reactive application turns on Reactor's automatic context propagation and registers BootUI's correlation
     * accessor, both JVM-wide; later unit tests in this JVM must not inherit them.
     */
    @AfterAll
    static void resetPropagation() {
        Hooks.disableAutomaticContextPropagation();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void closingTheStreamCancelsTheCall() throws Exception {
        // WebFlux notices the close at once, well before a keep-alive could reveal it.
        SUPPORT.closeAfterFirstEventCancels(
                port, service, Duration.ofMillis(McpStreamingCall.HEARTBEAT_MILLIS * 3 / 4));
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
