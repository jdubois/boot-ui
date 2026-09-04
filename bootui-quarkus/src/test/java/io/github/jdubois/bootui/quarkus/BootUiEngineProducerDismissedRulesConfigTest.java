package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code bootui.overrides-file} contract that {@link BootUiEngineProducer#dismissedRulesStore} shares with
 * the Spring adapters: the advisor dismissed-findings file lives in the <em>same directory</em> as the configured
 * overrides file, so relocating that key onto a mounted volume carries dismissals across container image rebuilds.
 * Documented in {@code docs/setup/environments.md}; the Spring MVC and WebFlux halves are pinned by
 * {@code BootUiAutoConfigurationTests} and {@code BootUiReactiveAutoConfigurationTests}.
 */
class BootUiEngineProducerDismissedRulesConfigTest {

    private final BootUiEngineProducer producer = new BootUiEngineProducer();

    @Test
    void followsTheDirectoryOfTheConfiguredOverridesFile(@TempDir Path tempDir) {
        Path stateDir = tempDir.resolve("var").resolve("bootui");
        DismissedRulesStore store = producer.dismissedRulesStore(new StubConfig(Map.of(
                "bootui.overrides-file",
                stateDir.resolve("application-bootui.properties").toString())));

        store.dismiss("RAPI-MAP-004");

        assertThat(stateDir.resolve("boot-ui.yml")).exists();
        assertThat(new DismissedRulesStore(stateDir.resolve("boot-ui.yml")).load())
                .containsExactly("RAPI-MAP-004");
    }
}
