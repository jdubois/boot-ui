package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import org.junit.jupiter.api.Test;

class McpToolTests {

    @Test
    void rejectsUnknownPanelIds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new McpTool(
                        "unknown", "Unknown panel.", McpToolSchema.NONE, "unknown", false, arguments -> null))
                .withMessageContaining("Unknown BootUI panel id");
    }

    @Test
    void actionToolsRequireAnActionCapablePanel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new McpTool(
                        "health_action",
                        "Invalid health action.",
                        McpToolSchema.NONE,
                        BootUiPanels.HEALTH,
                        true,
                        arguments -> null))
                .withMessageContaining("action-capable panel");
    }
}
