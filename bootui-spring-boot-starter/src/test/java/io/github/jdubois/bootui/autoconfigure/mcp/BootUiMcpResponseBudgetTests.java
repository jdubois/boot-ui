package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.McpResponseBudgetContract;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = BootUiMcpResponseBudgetTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "bootui.mcp.enabled=ON", "spring.main.web-application-type=servlet"})
class BootUiMcpResponseBudgetTests {

    static final McpResponseBudgetContract CONTRACT = new McpResponseBudgetContract();

    @LocalServerPort
    int port;

    @Autowired
    BootUiMcpService service;

    @Test
    void nativeJsonAndSseRespectTheBudgetInBothEras() throws Exception {
        CONTRACT.verify(port, service.dispatcher(), json -> json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        static BeanPostProcessor boundedService() {
            return replaceService(CONTRACT);
        }
    }

    static BeanPostProcessor replaceService(McpResponseBudgetContract contract) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof BootUiMcpService)) {
                    return bean;
                }
                BootUiProperties properties = new BootUiProperties();
                properties.getMcp().setMaxResponseBytes(512);
                properties.getMcp().setMaxConcurrentCalls(1);
                return new BootUiMcpService(
                        List.of(contract.tool()),
                        properties,
                        JsonMapper.builder()
                                .enable(SerializationFeature.INDENT_OUTPUT)
                                .build(),
                        "test",
                        (operation, failure) -> {
                            throw new AssertionError(operation, failure);
                        });
            }
        };
    }
}
