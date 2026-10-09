package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveRequestCorrelationFilter;
import io.github.jdubois.bootui.conformance.McpResponseBudgetContract;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
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

@SpringBootTest(
        classes = ReactiveBootUiMcpResponseBudgetTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "bootui.mcp.enabled=ON", "spring.main.web-application-type=reactive"})
@DirtiesContext
class ReactiveBootUiMcpResponseBudgetTests {

    static final McpResponseBudgetContract CONTRACT = new McpResponseBudgetContract();

    @LocalServerPort
    int port;

    @Autowired
    BootUiMcpService service;

    @AfterAll
    static void resetPropagation() {
        Hooks.disableAutomaticContextPropagation();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void nativeJsonAndSseRespectTheBudgetInBothEras() throws Exception {
        CONTRACT.verify(port, service.dispatcher(), json -> json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        static BeanPostProcessor boundedService() {
            return BootUiMcpResponseBudgetTests.replaceService(CONTRACT);
        }
    }
}
