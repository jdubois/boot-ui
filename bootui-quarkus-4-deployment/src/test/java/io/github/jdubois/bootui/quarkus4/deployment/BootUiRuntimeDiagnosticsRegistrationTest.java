package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus4.BootUiQuarkusRuntimeDiagnostics;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BootUiRuntimeDiagnosticsRegistrationTest {

    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void runtimeWarningsFollowConsoleActivationNotTheBanner(LaunchMode mode) {
        var index = new ArrayList<IndexDependencyBuildItem>();
        var beans = new ArrayList<AdditionalBeanBuildItem>();

        new BootUiQuarkusProcessor()
                .registerConsole(new BootUiConsoleBuildItem(mode, false, false), index::add, beans::add);

        boolean registered =
                beans.stream().anyMatch(item -> item.contains(BootUiQuarkusRuntimeDiagnostics.class.getName()));
        assertThat(registered).isEqualTo(mode != LaunchMode.NORMAL);
        if (mode != LaunchMode.NORMAL) {
            assertThat(index).singleElement().satisfies(item -> {
                assertThat(item.getGroupId()).isEqualTo("com.julien-dubois.bootui");
                assertThat(item.getArtifactId()).isEqualTo("bootui-quarkus-4");
            });
        } else {
            assertThat(index).isEmpty();
        }
    }
}
