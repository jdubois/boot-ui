package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;

/**
 * Build-time wiring for the BootUI Quarkus extension — the analogue of the Spring adapter's
 * {@code BootUiAutoConfiguration}.
 *
 * <p>It indexes the extension runtime jar (so Arc discovers the CDI beans/producer and the Vert.x safety
 * filter, and RESTEasy discovers the {@code @Path} resources) and pins those beans as unremovable. Crucially, that registration is <strong>gated to non-production launch modes</strong>: in
 * {@link LaunchMode#NORMAL} the console is not wired at all, so BootUI stays dark in production
 * (fail-closed), matching the Spring adapter's dev/local-only activation. {@code @QuarkusTest} runs in
 * {@link LaunchMode#TEST}, so the conformance suite still exercises the wired console.</p>
 *
 * <p>The shared Vue bundle under {@code META-INF/resources/bootui/} is served by Quarkus' static-resource
 * handler regardless of launch mode; suppressing even the static shell in production is a follow-up to
 * this tracer bullet (the data-bearing {@code /bootui/api/**} endpoints are already prod-gated here).</p>
 */
class BootUiQuarkusProcessor {

    private static final String FEATURE = "bootui";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerConsole(
            LaunchModeBuildItem launchMode,
            BuildProducer<IndexDependencyBuildItem> indexDependency,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: do not expose the console
        }
        // The extension runtime jar is not part of the application index by default; index it so Arc and
        // RESTEasy discover the annotated console types within it.
        indexDependency.produce(new IndexDependencyBuildItem("com.julien-dubois.bootui", "bootui-quarkus"));
        // Register the producer (which has @Produces methods) and the SPI-backed beans, and keep them even
        // if Arc's unused-bean analysis can't see the RESTEasy-mediated injection points.
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        BootUiEngineProducer.class,
                        QuarkusExposurePolicy.class,
                        QuarkusPanelAvailability.class,
                        BootUiQuarkusSafetyFilter.class)
                .setUnremovable()
                .build());
    }
}
