package io.github.jdubois.bootui.quarkus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Build-time wiring for the BootUI Quarkus extension.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's {@code BootUiAutoConfiguration}: it registers the
 * console's beans, JAX-RS resources and safety filter at build time. The registration build steps are added
 * incrementally (and gated to non-production launch modes) as the runtime surface grows; for now it only
 * contributes the extension {@code Feature} so the extension is recognized.
 */
class BootUiQuarkusProcessor {

    private static final String FEATURE = "bootui";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
