package io.github.jdubois.bootui.quarkus4.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class McpServerStateTest {

    @Test
    void normalizesSupportedModesAndFailsClosed() {
        assertThat(new McpServerState(" on ").configuredMode()).isEqualTo("ON");
        assertThat(new McpServerState("AUTO").isEnabled()).isFalse();
        assertThat(new McpServerState(null).configuredMode()).isEqualTo("OFF");
    }

    @Test
    void rejectsUnknownMode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new McpServerState("sometimes"))
                .withMessage("bootui.mcp.enabled must be ON, OFF, or AUTO");
    }
}
