package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus.BootUiQuarkusRuntimeDiagnostics;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BootUiRuntimeDiagnosticsRegistrationTest {

    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void runtimeWarningsFollowConsoleActivationNotTheBanner(LaunchMode mode) {
        var index = new ArrayList<IndexDependencyBuildItem>();
        var beans = new ArrayList<AdditionalBeanBuildItem>();

        new BootUiQuarkusProcessor()
                .registerConsole(
                        new LaunchModeBuildItem(mode, Optional.empty(), false, Optional.empty(), false),
                        index::add,
                        beans::add);

        boolean registered =
                beans.stream().anyMatch(item -> item.contains(BootUiQuarkusRuntimeDiagnostics.class.getName()));
        assertThat(registered).isEqualTo(mode != LaunchMode.NORMAL);
    }
}
