package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter;
import io.github.jdubois.bootui.quarkus.BootUiTelemetryProducer;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import io.github.jdubois.bootui.quarkus.QuarkusBasePackageProvider;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusMemoryRuntimeConfig;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.QuarkusServerPortSupplier;
import io.github.jdubois.bootui.quarkus.QuarkusTelemetrySettings;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.builder.Version;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.ClassInfo;

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

    // Referenced by class name only: this is the sole OpenTelemetry-importing type in the extension, and
    // the deployment classloader must never load it while augmenting an application that has no
    // quarkus-opentelemetry on its classpath (loading it would resolve its SpanProcessor return type and
    // link the OTel SDK that must stay absent — R2/BF2).
    private static final String OTEL_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiOtelProducer";

    // Referenced by class name only: this is the sole SmallRye-Health-importing type in the extension, and the
    // deployment classloader must never load it while augmenting an application that has no
    // quarkus-smallrye-health on its classpath (loading it would resolve its SmallRyeHealthReporter parameter
    // type and link a type that must stay absent — R2).
    private static final String HEALTH_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiHealthProducer";

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
        // if Arc's unused-bean analysis can't see the RESTEasy-mediated injection points. The telemetry read
        // services (Traces + AI Usage) are produced unconditionally here — they hold no OpenTelemetry types,
        // so they wire (and render empty) whether or not the app has quarkus-opentelemetry. The OTel-importing
        // capture producer is gated separately in {@link #registerOpenTelemetryCapture}.
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        BootUiEngineProducer.class,
                        BootUiTelemetryProducer.class,
                        QuarkusTelemetrySettings.class,
                        QuarkusExposurePolicy.class,
                        QuarkusMemoryRuntimeConfig.class,
                        QuarkusServerPortSupplier.class,
                        QuarkusApplicationInfo.class,
                        QuarkusBasePackageProvider.class,
                        QuarkusPanelAvailability.class,
                        BootUiQuarkusSafetyFilter.class)
                .setUnremovable()
                .build());
    }

    /**
     * Exposes the Quarkus core version to runtime config as {@code bootui.internal.quarkus-version} so
     * {@code QuarkusApplicationInfo} can report it as the overview {@code frameworkVersion}.
     *
     * <p>The version is only reliably available at build time via {@link Version#getVersion()}; the
     * runtime {@code Package#getImplementationVersion()} returns {@code null} under the Quarkus
     * classloader in dev/test. This default is harmless in production (the console is not wired, so the
     * key is simply never read).</p>
     */
    @BuildStep
    RunTimeConfigurationDefaultBuildItem quarkusVersion() {
        return new RunTimeConfigurationDefaultBuildItem(
                QuarkusApplicationInfo.QUARKUS_VERSION_KEY, Version.getVersion());
    }

    /**
     * Discovers the host application's own base packages at build time and exposes them to runtime config as
     * {@code bootui.internal.base-packages} (a comma-separated list) so {@link QuarkusBasePackageProvider}
     * can hand them to the engine's ArchUnit advisors — the Quarkus analogue of the Spring adapter resolving
     * {@code AutoConfigurationPackages}.
     *
     * <p>The roots are read from {@link ApplicationIndexBuildItem} — the Jandex index of the application root
     * archive, which contains the application's <em>own</em> classes only and excludes dependency jars (so
     * the discovered roots never leak BootUI's own or third-party packages into the scan). Each class's
     * package name is reduced to a minimal, bounded antichain by {@link BasePackageRoots#reduce}, which drops
     * the default and single-segment packages that would otherwise make ArchUnit scan the whole classpath.</p>
     *
     * <p>The step is gated to non-production launch modes: in {@link LaunchMode#NORMAL} the console is never
     * wired, so iterating the index would be wasted build work and the key would never be read. Because the
     * key is a runtime <em>default</em>, an application split across sibling modules (which the application
     * root index does not span) can override it explicitly in {@code application.properties}.</p>
     */
    @BuildStep
    void registerBasePackages(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the console is dark, so nothing reads the key
        }
        Set<String> packageNames = new HashSet<>();
        for (ClassInfo classInfo : applicationIndex.getIndex().getKnownClasses()) {
            String fqn = classInfo.name().toString();
            int lastDot = fqn.lastIndexOf('.');
            packageNames.add(lastDot < 0 ? "" : fqn.substring(0, lastDot));
        }
        List<String> roots = BasePackageRoots.reduce(packageNames);
        if (!roots.isEmpty()) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusBasePackageProvider.BASE_PACKAGES_KEY, String.join(",", roots)));
        }
    }

    /**
     * Registers the in-process span-capture producer ({@code BootUiOtelProducer}) <strong>only when
     * OpenTelemetry tracing is on the application classpath</strong> and not in production, and otherwise
     * <strong>excludes it from bean discovery entirely</strong>.
     *
     * <p>{@code BootUiOtelProducer} has a {@code @Produces SpanProcessor} method, and the extension runtime
     * jar is Jandex-indexed (so Arc discovers the always-on beans). Arc treats a producer method as
     * bean-defining, so the indexed producer would be discovered <em>unconditionally</em> — and Arc would
     * fail to resolve its {@code io.opentelemetry.sdk.trace.SpanProcessor} return type in an application
     * without {@code quarkus-opentelemetry}, linking the SDK that must stay absent (R2/BF2). A missing CDI
     * scope on the class is therefore <em>not</em> enough; the producer must be actively
     * {@linkplain ExcludedTypeBuildItem excluded} from discovery when OTel is absent. When OTel is present,
     * it is registered (and pinned unremovable, since its {@code SpanProcessor} is consumed by Quarkus
     * OpenTelemetry through a build step Arc's usage analysis cannot see). Quarkus then auto-discovers the
     * produced {@code SpanProcessor} and feeds finished spans through the engine exporter into the shared
     * {@code TelemetryStore}, lighting up the Traces and AI Usage panels with real data.</p>
     */
    @BuildStep
    void registerOpenTelemetryCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean enableCapture = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && capabilities.isPresent(Capability.OPENTELEMETRY_TRACER);
        if (enableCapture) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(OTEL_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
        } else {
            // No OpenTelemetry tracing (or production): keep the OTel-importing producer out of bean
            // discovery so Arc never tries to resolve its SpanProcessor return type. Traces/AI still wire
            // via BootUiTelemetryProducer and render empty.
            excludedTypes.produce(new ExcludedTypeBuildItem(OTEL_PRODUCER_CLASS));
        }
    }

    /**
     * Registers the in-process health-capture producer ({@code BootUiHealthProducer}) <strong>only when SmallRye
     * Health is on the application classpath</strong> and not in production, and otherwise <strong>excludes it
     * from bean discovery entirely</strong>.
     *
     * <p>The mechanism mirrors {@link #registerOpenTelemetryCapture} exactly. {@code BootUiHealthProducer} has a
     * {@code @Produces HealthProvider} method whose parameter is SmallRye's {@code SmallRyeHealthReporter}, and
     * the extension runtime jar is Jandex-indexed (so Arc discovers the always-on beans). Arc treats a producer
     * method as bean-defining, so the indexed producer would be discovered <em>unconditionally</em> — and Arc
     * would fail to resolve its {@code SmallRyeHealthReporter} parameter in an application without
     * {@code quarkus-smallrye-health}, linking a type that must stay absent (R2). A missing CDI scope on the
     * class is therefore <em>not</em> enough; the producer must be actively {@linkplain ExcludedTypeBuildItem
     * excluded} from discovery when SmallRye Health is absent. When it is present, the producer is registered
     * (and pinned unremovable, since the engine {@code HealthService} that consumes its {@code HealthProvider}
     * is itself injected into the RESTEasy-mediated {@code HealthResource}, which Arc's usage analysis cannot
     * see). The always-produced {@code HealthService} (see {@link io.github.jdubois.bootui.quarkus
     * .BootUiEngineProducer}) then receives a {@code null} provider when absent and renders the DISABLED root
     * with setup guidance, so the Health panel works on every Quarkus app.</p>
     */
    @BuildStep
    void registerSmallRyeHealthCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean enableCapture =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SMALLRYE_HEALTH);
        if (enableCapture) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(HEALTH_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
        } else {
            // No SmallRye Health (or production): keep the SmallRye-importing producer out of bean discovery so
            // Arc never tries to resolve its SmallRyeHealthReporter parameter. The Health panel still wires via
            // the always-produced HealthService and renders setup guidance.
            excludedTypes.produce(new ExcludedTypeBuildItem(HEALTH_PRODUCER_CLASS));
        }
    }
}
