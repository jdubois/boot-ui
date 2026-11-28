package io.github.jdubois.bootui.quarkus4.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.LaunchMode;

/** Build-time inclusion is a ceiling, never permission to activate a packaged JVM console. */
final class BootUiConsoleBuildItem extends SimpleBuildItem {
    private final boolean included;
    private final boolean nativeIncluded;

    BootUiConsoleBuildItem(LaunchMode launchMode, boolean nativeBuild, boolean nativeOptIn) {
        nativeIncluded = nativeBuild && nativeOptIn;
        included = launchMode != LaunchMode.NORMAL || nativeIncluded;
    }

    boolean included() {
        return included;
    }

    boolean nativeIncluded() {
        return nativeIncluded;
    }
}
