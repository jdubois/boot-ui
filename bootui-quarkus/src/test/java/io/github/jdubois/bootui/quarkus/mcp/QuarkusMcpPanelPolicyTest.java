package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessConfig;
import io.github.jdubois.bootui.quarkus.StubConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusMcpPanelPolicy}, pinning that it delegates to the same
 * {@link QuarkusPanelAccessConfig} config surface {@code QuarkusPanelAccessFilter} enforces on plain HTTP
 * requests — mirroring the Spring adapter's {@code SpringMcpPanelPolicy} test contract.
 */
class QuarkusMcpPanelPolicyTest {

    private static QuarkusMcpPanelPolicy policy(Map<String, String> values) {
        return new QuarkusMcpPanelPolicy(new QuarkusPanelAccessConfig(new StubConfig(values)));
    }

    @Test
    void toolIsEnabledByDefault() {
        QuarkusMcpPanelPolicy policy = policy(Map.of());

        assertThat(policy.isEnabled("memory")).isTrue();
    }

    @Test
    void toolIsRefusedWhenItsBackingPanelIsDisabled() {
        QuarkusMcpPanelPolicy policy = policy(Map.of("bootui.panels.memory.enabled", "false"));

        assertThat(policy.isEnabled("memory")).isFalse();
        assertThat(policy.disabledReason("memory"))
                .isEqualTo("Panel is disabled via bootui.panels.memory.enabled=false");
    }

    @Test
    void actionToolIsNotReadOnlyByDefault() {
        QuarkusMcpPanelPolicy policy = policy(Map.of());

        assertThat(policy.isReadOnly("memory")).isFalse();
    }

    @Test
    void actionToolIsRefusedWhenItsBackingPanelIsReadOnly() {
        QuarkusMcpPanelPolicy policy = policy(Map.of("bootui.panels.memory.read-only", "true"));

        assertThat(policy.isReadOnly("memory")).isTrue();
        assertThat(policy.readOnlyReason("memory"))
                .isEqualTo("Panel is read-only via bootui.panels.memory.read-only=true");
    }

    @Test
    void globalReadOnlyRefusesEveryActionTool() {
        QuarkusMcpPanelPolicy policy = policy(Map.of("bootui.read-only", "true"));

        assertThat(policy.isReadOnly("memory")).isTrue();
        assertThat(policy.isReadOnly("architecture")).isTrue();
        assertThat(policy.readOnlyReason("memory")).isEqualTo("BootUI is read-only via bootui.read-only=true");
    }
}
