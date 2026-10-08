package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpServerStatusDtoTests {

    @Test
    void theCompatibilityConstructorListsTheLegacyVersionAndStartsEveryCounterAtZero() {
        McpServerStatus status = new McpServerStatus(
                true, "ON", false, "bootui", "1.0", "http", "/bootui/api/mcp", "2025-06-18", 50, 0, List.of());

        assertThat(status.supportedProtocolVersions()).containsExactly("2025-06-18");
        assertThat(status.callCount()).isZero();
        assertThat(status.cancellations()).isZero();
    }

    @Test
    void theCompatibilityConstructorAcceptsAnUnknownProtocolVersion() {
        McpServerStatus status =
                new McpServerStatus(false, "OFF", false, "bootui", "1.0", "http", "/mcp", null, 50, 0, List.of());

        assertThat(status.protocolVersion()).isNull();
        assertThat(status.supportedProtocolVersions()).isEmpty();
    }
}
