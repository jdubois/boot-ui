package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuarkusPanelAccessConfig}, pinning parity with Spring's {@code BootUiProperties}. */
class QuarkusPanelAccessConfigTest {

    @Test
    void panelIsEnabledByDefault() {
        QuarkusPanelAccessConfig config = new QuarkusPanelAccessConfig(StubConfig.empty());

        assertThat(config.isPanelEnabled("memory")).isTrue();
    }

    @Test
    void panelIsDisabledWhenItsEnabledFlagIsFalse() {
        QuarkusPanelAccessConfig config =
                new QuarkusPanelAccessConfig(new StubConfig(Map.of("bootui.panels.memory.enabled", "false")));

        assertThat(config.isPanelEnabled("memory")).isFalse();
        assertThat(config.panelDisabledReason("memory"))
                .isEqualTo("Panel is disabled via bootui.panels.memory.enabled=false");
    }

    @Test
    void otherPanelsStayEnabledWhenOnlyOnePanelIsDisabled() {
        QuarkusPanelAccessConfig config =
                new QuarkusPanelAccessConfig(new StubConfig(Map.of("bootui.panels.memory.enabled", "false")));

        assertThat(config.isPanelEnabled("architecture")).isTrue();
    }

    @Test
    void panelIsNotReadOnlyByDefault() {
        QuarkusPanelAccessConfig config = new QuarkusPanelAccessConfig(StubConfig.empty());

        assertThat(config.isPanelReadOnly("memory")).isFalse();
    }

    @Test
    void panelIsReadOnlyWhenItsOwnReadOnlyFlagIsSet() {
        QuarkusPanelAccessConfig config =
                new QuarkusPanelAccessConfig(new StubConfig(Map.of("bootui.panels.memory.read-only", "true")));

        assertThat(config.isPanelReadOnly("memory")).isTrue();
        assertThat(config.panelReadOnlyReason("memory"))
                .isEqualTo("Panel is read-only via bootui.panels.memory.read-only=true");
    }

    @Test
    void otherPanelsStayWritableWhenOnlyOnePanelIsReadOnly() {
        QuarkusPanelAccessConfig config =
                new QuarkusPanelAccessConfig(new StubConfig(Map.of("bootui.panels.memory.read-only", "true")));

        assertThat(config.isPanelReadOnly("architecture")).isFalse();
    }

    @Test
    void globalReadOnlyForcesEveryPanelReadOnly() {
        QuarkusPanelAccessConfig config =
                new QuarkusPanelAccessConfig(new StubConfig(Map.of("bootui.read-only", "true")));

        assertThat(config.isPanelReadOnly("memory")).isTrue();
        assertThat(config.isPanelReadOnly("architecture")).isTrue();
        assertThat(config.panelReadOnlyReason("memory")).isEqualTo("BootUI is read-only via bootui.read-only=true");
    }

    @Test
    void globalReadOnlyReasonWinsOverPerPanelReasonWhenBothAreSet() {
        QuarkusPanelAccessConfig config = new QuarkusPanelAccessConfig(
                new StubConfig(Map.of("bootui.read-only", "true", "bootui.panels.memory.read-only", "true")));

        assertThat(config.panelReadOnlyReason("memory")).isEqualTo("BootUI is read-only via bootui.read-only=true");
    }
}
