package io.github.jdubois.bootui.quarkus4;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "bootui.native-console")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface BootUiNativeConsoleBuildTimeConfig {
    /**
     * Includes native-console wiring in a deliberately local diagnostic executable. Runtime ON and
     * explicit local-profile opt-in are still required; this never enables a packaged JVM console.
     */
    @WithDefault("false")
    boolean enabled();
}
