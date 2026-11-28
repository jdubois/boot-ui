package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

class BootUiConsoleBuildItemTest {
    @Test
    void packagedJvmCannotUseTheNativeOptIn() {
        for (boolean optedIn : new boolean[] {false, true}) {
            var console = new BootUiConsoleBuildItem(LaunchMode.NORMAL, false, optedIn);
            assertThat(console.included()).isFalse();
            assertThat(console.nativeIncluded()).isFalse();
        }
    }

    @Test
    void nativeWiringRequiresItsExplicitBuildOptIn() {
        assertThat(new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, false).included())
                .isFalse();
        var included = new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, true);
        assertThat(included.included()).isTrue();
        assertThat(included.nativeIncluded()).isTrue();
    }

    @Test
    void jvmDevelopmentAndTestsKeepTheirExistingWiring() {
        for (LaunchMode mode : new LaunchMode[] {LaunchMode.DEVELOPMENT, LaunchMode.TEST}) {
            for (boolean optedIn : new boolean[] {false, true}) {
                var console = new BootUiConsoleBuildItem(mode, false, optedIn);
                assertThat(console.included()).isTrue();
                assertThat(console.nativeIncluded()).isFalse();
            }
        }
    }
}
