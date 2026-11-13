package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.engine.compatibility.PlatformCompatibility;
import io.quarkus.builder.Version;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.jboss.logging.Logger;

/**
 * Checks the platform without linking console resources or optional integration APIs.
 *
 * <p>Quarkus discovers every processor's method signatures before executing build steps, so this cannot
 * replace framework linkage errors that happen during that earlier discovery.
 */
class BootUiPlatformCompatibilityProcessor {

    private static final Logger LOG = Logger.getLogger(BootUiPlatformCompatibilityProcessor.class);

    @BuildStep
    FeatureBuildItem feature() {
        // A disabled production console does not repair an incompatible extension dependency graph.
        for (var finding : PlatformCompatibility.quarkus(Version.getVersion())) {
            switch (finding.severity()) {
                case ERROR -> throw new IllegalStateException(finding.diagnostic());
                case WARNING -> LOG.warn(finding.diagnostic());
                case INFO -> LOG.info(finding.diagnostic());
            }
        }
        return new FeatureBuildItem("bootui");
    }
}
