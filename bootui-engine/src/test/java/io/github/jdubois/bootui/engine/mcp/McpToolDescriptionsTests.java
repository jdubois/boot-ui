package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolDescriptionsTests {

    private static final List<String> COMMON_TOOLS = List.of(
            "architecture_scan",
            "hibernate_scan",
            "memory_scan",
            "security_scan",
            "pentest_scan",
            "get_live_activity",
            "get_exceptions",
            "get_exception_detail",
            "get_security_logs",
            "get_sql_traces",
            "get_traces",
            "get_log_tail",
            "get_http_exchanges",
            "get_overview",
            "get_config",
            "get_mappings");

    @Test
    void everySpringToolHasAgentOrientedGuidance() {
        List<String> names = new java.util.ArrayList<>(COMMON_TOOLS);
        names.addAll(List.of("spring_scan", "rest_api_scan", "graalvm_scan", "crac_scan", "get_health", "get_beans"));

        assertDescriptions(names, McpToolDescriptions::spring);
    }

    @Test
    void everyQuarkusToolHasAgentOrientedGuidance() {
        List<String> names = new java.util.ArrayList<>(COMMON_TOOLS);
        names.addAll(List.of("spring_scan", "rest_api_scan", "get_health", "get_beans"));

        assertDescriptions(names, McpToolDescriptions::quarkus);
    }

    private static void assertDescriptions(
            List<String> names, java.util.function.Function<String, String> descriptionProvider) {
        assertThat(names)
                .allSatisfy(name -> assertThat(descriptionProvider.apply(name))
                        .as(name)
                        .hasSizeGreaterThan(60)
                        .endsWith("."));
    }
}
