package io.github.jdubois.bootui.quarkus4.deployment;

import io.github.jdubois.bootui.engine.compatibility.PlatformCompatibility;
import io.quarkus.builder.Version;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import org.jboss.logging.Logger;

class BootUiPlatformCompatibilityProcessor {
    private static final Logger LOG = Logger.getLogger(BootUiPlatformCompatibilityProcessor.class);

    @BuildStep
    FeatureBuildItem feature(CurateOutcomeBuildItem curated) {
        boolean quarkus3AdapterPresent = curated.getApplicationModel().getDependencies().stream()
                .anyMatch(dependency -> dependency.getGroupId().equals("com.julien-dubois.bootui")
                        && (dependency.getArtifactId().equals("bootui-quarkus")
                                || dependency.getArtifactId().equals("bootui-quarkus-deployment")));
        for (var finding : PlatformCompatibility.quarkus4(Version.getVersion(), quarkus3AdapterPresent)) {
            switch (finding.severity()) {
                case ERROR -> throw new IllegalStateException(finding.diagnostic());
                case WARNING -> LOG.warn(finding.diagnostic());
                case INFO -> LOG.info(finding.diagnostic());
            }
        }
        return new FeatureBuildItem("bootui");
    }
}
