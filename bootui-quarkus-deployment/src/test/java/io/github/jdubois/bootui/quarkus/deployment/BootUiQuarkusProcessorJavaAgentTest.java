package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.javaagent.QuarkusAgentClaim;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

class BootUiQuarkusProcessorJavaAgentTest {

    @Test
    void theModeFollowsTheLaunchModeUnlessConfigured() {
        assertThat(BootUiQuarkusProcessor.agentMode("auto", LaunchMode.TEST)).isEqualTo("test");
        assertThat(BootUiQuarkusProcessor.agentMode("auto", LaunchMode.DEVELOPMENT))
                .isEqualTo("dev");
        assertThat(BootUiQuarkusProcessor.agentMode(null, LaunchMode.DEVELOPMENT))
                .isEqualTo("dev");
        assertThat(BootUiQuarkusProcessor.agentMode(" DEV ", LaunchMode.TEST)).isEqualTo("dev");
        assertThat(BootUiQuarkusProcessor.agentMode("test", LaunchMode.DEVELOPMENT))
                .isEqualTo("test");
        assertThat(BootUiQuarkusProcessor.agentMode("bogus", LaunchMode.TEST)).isEqualTo("test");
    }

    @Test
    void theApplicationArchivesPackagesAreReducedWithoutBootUisOwnModulesThenTheConfiguredOnesAdded()
            throws IOException {
        Index index = Index.of(
                QuarkusAgentClaim.class, // io.github.jdubois.bootui.quarkus: BootUI's own module
                org.jboss.jandex.Index.class,
                org.jboss.jandex.DotName.class,
                java.util.concurrent.atomic.AtomicLong.class,
                java.util.concurrent.ConcurrentHashMap.class);

        assertThat(BootUiQuarkusProcessor.agentPackages(index, List.of("com.example.extra", " ", "org.jboss.jandex")))
                .containsExactly("java.util.concurrent", "org.jboss.jandex", "com.example.extra");
    }

    @Test
    void anUnsetListPropertyKeepsItsDefaultsAndAnEmptyOneMeansNone() {
        List<String> defaults = List.of("executors");

        assertThat(BootUiQuarkusProcessor.listOrDefaults(config(Map.of()), "bootui.agent.sensors", defaults))
                .containsExactly("executors");
        assertThat(BootUiQuarkusProcessor.listOrDefaults(
                        config(Map.of("bootui.agent.sensors", "")), "bootui.agent.sensors", defaults))
                .as("bootui.agent.sensors= asks for no sensor")
                .isEmpty();
        assertThat(BootUiQuarkusProcessor.listOrDefaults(
                        config(Map.of("bootui.agent.sensors", "executors,threads")), "bootui.agent.sensors", defaults))
                .containsExactly("executors", "threads");
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 500))
                .build();
    }
}
