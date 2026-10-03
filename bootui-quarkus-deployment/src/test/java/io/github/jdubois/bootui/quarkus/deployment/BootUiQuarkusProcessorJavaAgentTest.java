package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.javaagent.QuarkusAgentClaim;
import io.quarkus.runtime.LaunchMode;
import java.io.IOException;
import java.util.List;
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
}
