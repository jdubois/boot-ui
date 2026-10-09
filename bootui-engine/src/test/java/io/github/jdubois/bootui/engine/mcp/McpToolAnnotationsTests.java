package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpToolAnnotationsTests {

    @Test
    void everyReadIsReadOnlyAndIdempotent() {
        McpToolCatalog.entries().stream()
                .filter(entry -> !entry.action())
                .forEach(entry -> assertThat(McpToolAnnotations.of(entry.name(), false))
                        .as(entry.name())
                        .isEqualTo(new McpToolAnnotations(true, false, true, false)));
    }

    @Test
    void actionsSayWhetherTheyDiscardStateRepeatSafelyOrLeaveTheMachine() {
        assertThat(McpToolAnnotations.of("clear_sql_traces", true))
                .isEqualTo(new McpToolAnnotations(false, true, true, false));
        assertThat(McpToolAnnotations.of("pause_rest_client_recording", true))
                .isEqualTo(new McpToolAnnotations(false, false, true, false));
        assertThat(McpToolAnnotations.of("resume_transaction_recording", true))
                .isEqualTo(new McpToolAnnotations(false, false, true, false));
        assertThat(McpToolAnnotations.of("vulnerabilities_scan", true))
                .isEqualTo(new McpToolAnnotations(false, false, false, true));
        assertThat(McpToolAnnotations.of("start_method_probe", true))
                .isEqualTo(new McpToolAnnotations(false, false, false, false));
        assertThat(McpToolAnnotations.of("enable_agent_sensor", true))
                .isEqualTo(new McpToolAnnotations(false, false, false, false));
        assertThat(McpToolAnnotations.of("disable_agent_sensor", true))
                .isEqualTo(new McpToolAnnotations(false, false, false, false));
        assertThat(McpToolAnnotations.of("memory_scan", true))
                .isEqualTo(new McpToolAnnotations(false, false, false, false));
    }

    @Test
    void onlyTheVulnerabilityScanContactsANetworkService() {
        assertThat(McpToolCatalog.entries().stream()
                        .filter(entry -> McpToolAnnotations.of(entry.name(), entry.action())
                                .openWorldHint())
                        .map(McpToolCatalog.Entry::name))
                .containsExactly("vulnerabilities_scan");
    }
}
