package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter;
import io.github.jdubois.bootui.quarkus.BootUiTelemetryProducer;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import io.github.jdubois.bootui.quarkus.QuarkusBasePackageProvider;
import io.github.jdubois.bootui.quarkus.QuarkusDependencyProvider;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusMemoryRuntimeConfig;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.QuarkusServerPortSupplier;
import io.github.jdubois.bootui.quarkus.QuarkusTelemetrySettings;
import io.github.jdubois.bootui.quarkus.config.QuarkusConfigProvider;
import io.github.jdubois.bootui.quarkus.logging.QuarkusLogTailCapture;
import io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTaskProvider;
import io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTasks;
import io.github.jdubois.bootui.quarkus.scheduled.RawScheduledTask;
import io.github.jdubois.bootui.quarkus.scheduled.ScheduledTasksRecorder;
import io.github.jdubois.bootui.quarkus.web.HttpExchangesResource;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.github.jdubois.bootui.quarkus.web.QuarkusHttpExchangeCaptureFilter;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.builder.Version;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.runtime.LaunchMode;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

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

    // Referenced by class name only: this is the sole jakarta.persistence-importing type in the extension, and
    // the deployment classloader must never load it while augmenting an application that has no
    // quarkus-hibernate-orm on its classpath (loading it would resolve its EntityManagerFactory parameter type
    // and link the JPA API that must stay absent — R2).
    private static final String HIBERNATE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiHibernateProducer";

    private static final String CACHE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiCacheProducer";

    private static final String FLYWAY_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiFlywayProducer";
    private static final String LIQUIBASE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiLiquibaseProducer";

    // Referenced by class name only: BootUiAgroalProducer @Produces a ConnectionPoolProvider whose
    // implementation (QuarkusAgroalConnectionPoolProvider) imports io.agroal.*; the deployment classloader must
    // never load it while augmenting an application without a JDBC datasource extension on its classpath
    // (loading it would link the Agroal API that must stay absent — R2).
    private static final String AGROAL_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiAgroalProducer";

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
                        QuarkusDependencyProvider.class,
                        QuarkusConfigProvider.class,
                        QuarkusScheduledTaskProvider.class,
                        QuarkusPanelAvailability.class,
                        QuarkusLogTailCapture.class,
                        QuarkusHttpExchangeCaptureFilter.class,
                        HttpExchangesResource.class,
                        LiveActivityResource.class,
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
     * Captures build-time authorization-annotation counts for the Quarkus Security advisor: how many
     * {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll}/{@code @Authenticated} sites and JAX-RS
     * endpoints the application declares, emitted as runtime config defaults the advisor reads. Dev/test only.
     */
    @BuildStep
    void registerSecurityAnnotations(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        IndexView index = applicationIndex.getIndex();
        int roles = index.getAnnotations(DotName.createSimple("jakarta.annotation.security.RolesAllowed"))
                .size();
        int permit = index.getAnnotations(DotName.createSimple("jakarta.annotation.security.PermitAll"))
                .size();
        int deny = index.getAnnotations(DotName.createSimple("jakarta.annotation.security.DenyAll"))
                .size();
        int authenticated = index.getAnnotations(DotName.createSimple("io.quarkus.security.Authenticated"))
                .size();
        int endpoints = 0;
        int secured = 0;
        for (String http : List.of(
                "jakarta.ws.rs.GET",
                "jakarta.ws.rs.POST",
                "jakarta.ws.rs.PUT",
                "jakarta.ws.rs.DELETE",
                "jakarta.ws.rs.PATCH",
                "jakarta.ws.rs.HEAD",
                "jakarta.ws.rs.OPTIONS")) {
            for (AnnotationInstance ann : index.getAnnotations(DotName.createSimple(http))) {
                if (ann.target() != null && ann.target().kind() == AnnotationTarget.Kind.METHOD) {
                    endpoints++;
                    if (isSecuredEndpoint(ann.target().asMethod())) {
                        secured++;
                    }
                }
            }
        }
        emit(runtimeDefaults, "bootui.internal.sec.roles-allowed", roles);
        emit(runtimeDefaults, "bootui.internal.sec.permit-all", permit);
        emit(runtimeDefaults, "bootui.internal.sec.deny-all", deny);
        emit(runtimeDefaults, "bootui.internal.sec.authenticated", authenticated);
        emit(runtimeDefaults, "bootui.internal.sec.endpoints", endpoints);
        emit(runtimeDefaults, "bootui.internal.sec.secured-endpoints", secured);
    }

    private static boolean isSecuredEndpoint(MethodInfo method) {
        for (String sec : List.of(
                "jakarta.annotation.security.RolesAllowed",
                "jakarta.annotation.security.PermitAll",
                "io.quarkus.security.Authenticated")) {
            DotName name = DotName.createSimple(sec);
            if (method.hasAnnotation(name) || method.declaringClass().hasAnnotation(name)) {
                return true;
            }
        }
        return false;
    }

    private static void emit(
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults, String key, int value) {
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(key, Integer.toString(value)));
    }

    private static final DotName APPLICATION_SCOPED =
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName SINGLETON = DotName.createSimple("jakarta.inject.Singleton");
    private static final DotName REQUEST_SCOPED = DotName.createSimple("jakarta.enterprise.context.RequestScoped");
    private static final DotName DEPENDENT = DotName.createSimple("jakarta.enterprise.context.Dependent");
    private static final DotName CONFIG_PROPERTY =
            DotName.createSimple("org.eclipse.microprofile.config.inject.ConfigProperty");
    private static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName BLOCKING = DotName.createSimple("io.smallrye.common.annotation.Blocking");

    /**
     * Captures build-time idiom counts for the Quarkus-native application advisor: CDI scope annotation counts,
     * {@code @ConfigProperty} sites, JAX-RS resources without an explicit scope, reactive ({@code Uni}/{@code Multi})
     * endpoints, {@code @Blocking} sites, and shared mutable fields on {@code @ApplicationScoped} beans. Emitted as
     * runtime config defaults the advisor reads. Dev/test only — skipped in {@link LaunchMode#NORMAL}.
     */
    @BuildStep
    void registerAppIdioms(
            LaunchModeBuildItem launchMode,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            ApplicationIndexBuildItem applicationIndex,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        IndexView app = applicationIndex.getIndex();
        IndexView beans = beanArchiveIndex.getIndex();
        emit(runtimeDefaults, "bootui.internal.app.application-scoped", classAnnotations(app, APPLICATION_SCOPED));
        emit(runtimeDefaults, "bootui.internal.app.singleton", classAnnotations(app, SINGLETON));
        emit(runtimeDefaults, "bootui.internal.app.request-scoped", classAnnotations(app, REQUEST_SCOPED));
        emit(runtimeDefaults, "bootui.internal.app.dependent", classAnnotations(app, DEPENDENT));
        emit(
                runtimeDefaults,
                "bootui.internal.app.config-property",
                beans.getAnnotations(CONFIG_PROPERTY).size());
        emit(
                runtimeDefaults,
                "bootui.internal.app.blocking",
                app.getAnnotations(BLOCKING).size());

        int endpoints = 0;
        int reactive = 0;
        for (String http : List.of(
                "jakarta.ws.rs.GET",
                "jakarta.ws.rs.POST",
                "jakarta.ws.rs.PUT",
                "jakarta.ws.rs.DELETE",
                "jakarta.ws.rs.PATCH",
                "jakarta.ws.rs.HEAD",
                "jakarta.ws.rs.OPTIONS")) {
            for (AnnotationInstance ann : app.getAnnotations(DotName.createSimple(http))) {
                if (ann.target() != null && ann.target().kind() == AnnotationTarget.Kind.METHOD) {
                    endpoints++;
                    if (isReactive(ann.target().asMethod().returnType())) {
                        reactive++;
                    }
                }
            }
        }
        emit(runtimeDefaults, "bootui.internal.app.endpoints", endpoints);
        emit(runtimeDefaults, "bootui.internal.app.reactive-endpoints", reactive);

        int defaultScopeResources = 0;
        List<String> mutableFields = new ArrayList<>();
        for (AnnotationInstance ann : app.getAnnotations(PATH)) {
            if (ann.target() == null || ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo cls = ann.target().asClass();
            if (cls.declaredAnnotation(APPLICATION_SCOPED) == null
                    && cls.declaredAnnotation(REQUEST_SCOPED) == null
                    && cls.declaredAnnotation(SINGLETON) == null
                    && cls.declaredAnnotation(DEPENDENT) == null) {
                defaultScopeResources++;
            }
        }
        for (AnnotationInstance ann : app.getAnnotations(APPLICATION_SCOPED)) {
            if (ann.target() == null || ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo cls = ann.target().asClass();
            for (FieldInfo f : cls.fields()) {
                boolean isPublic = (f.flags() & 0x0001) != 0;
                boolean isFinal = (f.flags() & 0x0010) != 0;
                boolean isStatic = (f.flags() & 0x0008) != 0;
                if (!isStatic && (isPublic || !isFinal)) {
                    mutableFields.add(cls.simpleName() + "." + f.name());
                }
            }
        }
        emit(runtimeDefaults, "bootui.internal.app.default-scope-resources", defaultScopeResources);
        if (!mutableFields.isEmpty()) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    "bootui.internal.app.mutable-fields", String.join(",", mutableFields)));
        }
    }

    private static int classAnnotations(IndexView index, DotName annotation) {
        int n = 0;
        for (AnnotationInstance ann : index.getAnnotations(annotation)) {
            if (ann.target() != null && ann.target().kind() == AnnotationTarget.Kind.CLASS) {
                n++;
            }
        }
        return n;
    }

    private static boolean isReactive(Type returnType) {
        if (returnType == null) {
            return false;
        }
        String name = returnType.name().toString();
        return name.equals("io.smallrye.mutiny.Uni") || name.equals("io.smallrye.mutiny.Multi");
    }

    /**
     * Captures the host application's resolved Maven dependency inventory at build time and exposes it to
     * runtime config as {@code bootui.internal.dependencies} (a comma-separated list of
     * {@code groupId:artifactId:version} coordinates) so {@link QuarkusDependencyProvider} can feed the
     * Vulnerabilities panel — the Quarkus analogue of the Spring adapter's {@code DependencyCatalog}
     * classpath scan, which is unreliable under the Quarkus runtime classloader.
     *
     * <p>The coordinates are read from {@link CurateOutcomeBuildItem#getApplicationModel()}'s
     * {@code getRuntimeDependencies()} — the fully-resolved runtime classpath of the application, in every
     * packaging layout (jar / fast-jar / native), filtered to {@code jar} artifacts. The application's own
     * artifact is intentionally excluded (it is not an OSV-published package). Each coordinate is
     * defensively skipped if it contains a comma, {@code $} or whitespace, so the comma-delimited channel
     * (split back as a plain {@code String}, never a config list) can never be corrupted nor trip SmallRye
     * {@code ${...}} expression expansion — such characters do not occur in real Maven coordinates.</p>
     *
     * <p>This mirrors {@link #registerBasePackages} exactly: build-time discovery surfaced as a runtime
     * config <em>default</em>. The step is gated to non-production launch modes (in {@link LaunchMode#NORMAL}
     * the console is never wired, so the key is never read). For a typical application this is a few KB,
     * well within the generated default config source's string-constant ceiling; a pathological app with
     * thousands of runtime dependencies could instead use a {@code @Recorder} + {@code SyntheticBeanBuildItem}
     * channel (the structured-data pattern reserved for the Mappings panel).</p>
     */
    @BuildStep
    void registerDependencyInventory(
            LaunchModeBuildItem launchMode,
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the console is dark, so nothing reads the key
        }
        Set<String> coordinates = new LinkedHashSet<>();
        for (ResolvedDependency dependency : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
            if (!"jar".equals(dependency.getType())) {
                continue;
            }
            String coordinate =
                    dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion();
            if (coordinate.indexOf(',') >= 0
                    || coordinate.indexOf('$') >= 0
                    || coordinate.chars().anyMatch(Character::isWhitespace)) {
                continue; // would corrupt the comma channel or trip ${...} expansion — never a real coordinate
            }
            coordinates.add(coordinate);
        }
        if (!coordinates.isEmpty()) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusDependencyProvider.DEPENDENCIES_KEY, String.join(",", coordinates)));
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

    /**
     * Registers the optional Hibernate-entity-discovery producer ({@code BootUiHibernateProducer})
     * <strong>only when Hibernate ORM is on the application classpath</strong> and not in production, otherwise
     * <strong>excludes it from bean discovery entirely</strong>, and feeds the build-time capability decision
     * back to {@link QuarkusPanelAvailability} so the dynamically-available Hibernate advisor panel lights up.
     *
     * <p>{@code BootUiHibernateProducer} has a {@code @Produces EntityDiscoverySource} method whose parameter
     * type is {@code jakarta.persistence.EntityManagerFactory}, and the extension runtime jar is Jandex-indexed
     * (so Arc discovers the always-on beans). Arc treats a producer method as bean-defining, so the indexed
     * producer would be discovered <em>unconditionally</em> — and Arc would fail to resolve its
     * {@code EntityManagerFactory} parameter type in an application without {@code quarkus-hibernate-orm},
     * linking the JPA API that must stay absent (R2). A missing CDI scope on the class is therefore <em>not</em>
     * enough; the producer must be actively {@linkplain ExcludedTypeBuildItem excluded} from discovery when
     * Hibernate ORM is absent. When it is present, the producer is registered (and pinned unremovable, since the
     * engine {@code HibernateScanner} that consumes its {@code EntityDiscoverySource} is itself injected into the
     * RESTEasy-mediated {@code HibernateResource}, which Arc's usage analysis cannot see). The always-produced
     * {@code HibernateScanner} (see {@link io.github.jdubois.bootui.quarkus.BootUiEngineProducer}) then receives
     * an empty discovery when absent and renders a not-configured report, so the panel never fails — it is simply
     * reported unavailable in the manifest until the extension is added.</p>
     */
    @BuildStep
    void registerHibernateAdvisor(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.HIBERNATE_ORM);
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(HIBERNATE_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.HIBERNATE_PRESENT_KEY, "true"));
        } else {
            // No Hibernate ORM (or production): keep the jakarta.persistence-importing producer out of bean
            // discovery so Arc never tries to resolve its EntityManagerFactory parameter. The Hibernate scanner
            // still wires via the always-produced HibernateScanner and renders a not-configured report; the panel
            // is reported unavailable in the manifest (HIBERNATE_PRESENT_KEY defaults to false).
            excludedTypes.produce(new ExcludedTypeBuildItem(HIBERNATE_PRODUCER_CLASS));
        }
    }

    /**
     * Captures the host application's {@code @io.quarkus.scheduler.Scheduled} methods at build time and exposes
     * them to the runtime as a synthetic {@link QuarkusScheduledTasks} bean (via {@link ScheduledTasksRecorder}),
     * plus a {@code bootui.internal.scheduled-present=true} runtime-config default that lights up the Scheduled
     * Tasks panel in the manifest. This is the build-time-capture pattern (like Architecture base packages and
     * the Vulnerabilities dependency inventory), chosen because the {@code ScheduledTaskDto} contract carries
     * only the static cron/every/initial-delay configuration and target method — all known at build time —
     * whereas the runtime {@code io.quarkus.scheduler.Scheduler} exposes only trigger ids and next-fire times,
     * which the contract does not carry. So no runtime class imports {@code io.quarkus.scheduler.*} (no R2
     * classloading trap, no provided-scope dependency, no {@link ExcludedTypeBuildItem}); the
     * {@link Capability#SCHEDULER} capability gate is the entire safety mechanism.
     *
     * <p>Gated to a non-production launch with {@code quarkus-scheduler} present: when absent (or in
     * {@link LaunchMode#NORMAL}) the synthetic bean is not produced, so {@link QuarkusScheduledTaskProvider}'s
     * {@code Instance} is unsatisfied and the panel is reported unavailable ({@code SCHEDULED_PRESENT_KEY}
     * defaults to {@code false}). The annotation metadata is read from the {@link BeanArchiveIndexBuildItem}
     * Jandex index (the bean archives, which span the application beans the scheduler enhances), and only
     * annotation-discovered jobs are captured — programmatic {@code Scheduler.newJob()} jobs are not, matching
     * the panel's documented scope.</p>
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerScheduledTasks(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            ScheduledTasksRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SCHEDULER);
        if (!present) {
            return; // no scheduler (or production): the panel stays unavailable (key defaults to false)
        }
        List<RawScheduledTask> tasks = scanScheduledTasks(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusScheduledTasks.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(tasks))
                .unremovable()
                .done());
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.SCHEDULED_PRESENT_KEY, "true"));
    }

    private static final DotName SCHEDULED_ANNOTATION = DotName.createSimple("io.quarkus.scheduler.Scheduled");

    private static final DotName SCHEDULES_ANNOTATION =
            DotName.createSimple("io.quarkus.scheduler.Scheduled$Schedules");

    /**
     * Reads every {@code @Scheduled} method from {@code index} into a verbatim {@link RawScheduledTask} list.
     * Both a directly-declared {@code @Scheduled} and the {@code @Schedules} repeatable container (whose nested
     * instances are unwrapped manually — {@code getAnnotationsWithRepeatable} is avoided because the container
     * type is not necessarily indexed) are handled; the nested instances inherit the container's method target.
     * Annotation members absent from the Jandex instance fall back to the annotation defaults ({@code ""} for
     * the strings, {@code 0} for {@code delay}, {@code MINUTES} for {@code delayUnit}).
     */
    private static List<RawScheduledTask> scanScheduledTasks(IndexView index) {
        List<RawScheduledTask> tasks = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(SCHEDULED_ANNOTATION)) {
            if (annotation.target() != null && annotation.target().kind() == AnnotationTarget.Kind.METHOD) {
                tasks.add(toRawScheduledTask(annotation.target().asMethod(), annotation));
            }
        }
        for (AnnotationInstance container : index.getAnnotations(SCHEDULES_ANNOTATION)) {
            if (container.target() == null || container.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }
            MethodInfo method = container.target().asMethod();
            AnnotationValue nested = container.value();
            if (nested != null) {
                for (AnnotationInstance scheduled : nested.asNestedArray()) {
                    tasks.add(toRawScheduledTask(method, scheduled));
                }
            }
        }
        return tasks;
    }

    private static RawScheduledTask toRawScheduledTask(MethodInfo method, AnnotationInstance annotation) {
        String methodDescription = method.declaringClass().name().toString() + "#" + method.name();
        return new RawScheduledTask(
                methodDescription,
                stringMember(annotation, "cron"),
                stringMember(annotation, "every"),
                stringMember(annotation, "delayed"),
                longMember(annotation, "delay"),
                enumMember(annotation, "delayUnit", "MINUTES"));
    }

    private static String stringMember(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? "" : value.asString();
    }

    private static long longMember(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? 0L : value.asLong();
    }

    private static String enumMember(AnnotationInstance annotation, String name, String defaultValue) {
        AnnotationValue value = annotation.value(name);
        return value == null ? defaultValue : value.asEnum();
    }

    /**
     * Capability-gated registration of the Cache panel's cache-API-importing producer (R2), mirroring
     * {@link #registerHibernateAdvisor} exactly.
     *
     * <p>{@code BootUiCacheProducer} has a {@code @Produces CacheProvider} method whose parameter type is
     * {@code io.quarkus.cache.CacheManager}, and the extension runtime jar is Jandex-indexed (so Arc discovers
     * the always-on beans). Arc treats a producer method as bean-defining, so the indexed producer would be
     * discovered <em>unconditionally</em> — and Arc would fail to resolve its {@code CacheManager} parameter
     * type in an application without {@code quarkus-cache}, linking the {@code io.quarkus.cache} API that must
     * stay absent (R2). A missing CDI scope on the class is therefore <em>not</em> enough; the producer must be
     * actively {@linkplain ExcludedTypeBuildItem excluded} from discovery when the {@code CACHE} capability is
     * absent. When it is present, the producer is registered (and pinned unremovable, since the engine
     * {@code CacheService} that consumes its {@code CacheProvider} is itself injected into the RESTEasy-mediated
     * {@code CacheResource}, which Arc's usage analysis cannot see). The always-produced {@code CacheService}
     * (see {@link io.github.jdubois.bootui.quarkus.BootUiEngineProducer}) then receives a {@code null} provider
     * when absent and renders the panel unavailable, so it never fails — it is simply reported unavailable in
     * the manifest until {@code quarkus-cache} is added.</p>
     */
    @BuildStep
    void registerCacheAdvisor(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.CACHE);
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(CACHE_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.CACHE_PRESENT_KEY, "true"));
        } else {
            // No quarkus-cache (or production): keep the io.quarkus.cache-importing producer out of bean
            // discovery so Arc never tries to resolve its CacheManager parameter. The cache service still wires
            // via the always-produced CacheService and renders the panel unavailable; the panel is reported
            // unavailable in the manifest (CACHE_PRESENT_KEY defaults to false).
            excludedTypes.produce(new ExcludedTypeBuildItem(CACHE_PRODUCER_CLASS));
        }
    }

    /**
     * Capability-gated registration of the Flyway panel's Flyway-API-importing producer (R2), mirroring
     * {@link #registerCacheAdvisor} exactly.
     *
     * <p>{@code BootUiFlywayProducer} has a {@code @Produces FlywayProvider} method whose body constructs
     * {@code QuarkusFlywayProvider}, which imports {@code org.flywaydb.*} and {@code io.quarkus.flyway.*}
     * types, and the extension runtime jar is Jandex-indexed (so Arc discovers the always-on beans). Arc
     * treats a producer method as bean-defining, so the indexed producer would be discovered
     * <em>unconditionally</em> — and loading it in an application without {@code quarkus-flyway} would link the
     * Flyway API that must stay absent (R2). A missing CDI scope on the class is therefore <em>not</em> enough;
     * the producer must be actively {@linkplain ExcludedTypeBuildItem excluded} from discovery when the
     * {@code FLYWAY} capability is absent. When it is present, the producer is registered (and pinned
     * unremovable, since the engine {@code FlywayService} that consumes its {@code FlywayProvider} is itself
     * injected into the RESTEasy-mediated {@code FlywayResource}, which Arc's usage analysis cannot see). The
     * always-produced {@code FlywayService} (see {@link io.github.jdubois.bootui.quarkus.BootUiEngineProducer})
     * then receives a {@code null} provider when absent and renders the panel unavailable, so it never fails —
     * it is simply reported unavailable in the manifest until {@code quarkus-flyway} is added.</p>
     */
    @BuildStep
    void registerFlyway(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.FLYWAY);
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(FLYWAY_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.FLYWAY_PRESENT_KEY, "true"));
        } else {
            // No quarkus-flyway (or production): keep the Flyway-importing producer out of bean discovery so Arc
            // never loads QuarkusFlywayProvider and links the Flyway API. The Flyway service still wires via the
            // always-produced FlywayService and renders the panel unavailable; the panel is reported unavailable
            // in the manifest (FLYWAY_PRESENT_KEY defaults to false).
            excludedTypes.produce(new ExcludedTypeBuildItem(FLYWAY_PRODUCER_CLASS));
        }
    }

    /**
     * Capability-gated registration of the Liquibase panel's liquibase-API-importing producer (R2), mirroring
     * {@link #registerCacheAdvisor} and {@link #registerHibernateAdvisor} exactly.
     *
     * <p>{@code BootUiLiquibaseProducer} has a {@code @Produces LiquibaseProvider} method whose implementation
     * ({@code QuarkusLiquibaseProvider}) statically references {@code io.quarkus.liquibase} / {@code liquibase}
     * types, and the extension runtime jar is Jandex-indexed (so Arc discovers the always-on beans). Arc treats
     * a producer method as bean-defining, so the indexed producer would be discovered <em>unconditionally</em>
     * — and instantiating it would link the {@code io.quarkus.liquibase} API that must stay absent in an
     * application without {@code quarkus-liquibase} (R2). A missing CDI scope on the class is therefore
     * <em>not</em> enough; the producer must be actively {@linkplain ExcludedTypeBuildItem excluded} from
     * discovery when the {@code LIQUIBASE} capability is absent. When it is present, the producer is registered
     * (and pinned unremovable, since the engine {@code LiquibaseService} that consumes its
     * {@code LiquibaseProvider} is itself injected into the RESTEasy-mediated {@code LiquibaseResource}, which
     * Arc's usage analysis cannot see). The always-produced {@code LiquibaseService} (see
     * {@link io.github.jdubois.bootui.quarkus.BootUiEngineProducer}) then receives a {@code null} provider when
     * absent and renders the panel unavailable, so it never fails — it is simply reported unavailable in the
     * manifest until {@code quarkus-liquibase} is added.</p>
     */
    @BuildStep
    void registerLiquibase(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.LIQUIBASE);
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(LIQUIBASE_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.LIQUIBASE_PRESENT_KEY, "true"));
        } else {
            // No quarkus-liquibase (or production): keep the io.quarkus.liquibase-importing producer out of bean
            // discovery so Arc never instantiates QuarkusLiquibaseProvider. The liquibase service still wires via
            // the always-produced LiquibaseService and renders the panel unavailable; the panel is reported
            // unavailable in the manifest (LIQUIBASE_PRESENT_KEY defaults to false).
            excludedTypes.produce(new ExcludedTypeBuildItem(LIQUIBASE_PRODUCER_CLASS));
        }
    }

    /**
     * Capability-gated registration of the Database Connection Pools panel's Agroal-API-importing producer
     * (R2), mirroring {@link #registerCacheAdvisor} exactly.
     *
     * <p>{@code BootUiAgroalProducer} has a {@code @Produces ConnectionPoolProvider} method that
     * {@code new}-constructs {@code QuarkusAgroalConnectionPoolProvider}, the sole {@code io.agroal}-importing
     * type in the extension, and the extension runtime jar is Jandex-indexed (so Arc discovers the always-on
     * beans). Arc treats a producer method as bean-defining, so the indexed producer would be discovered
     * <em>unconditionally</em> — and loading it in an application without a JDBC datasource extension would link
     * the {@code io.agroal} API that must stay absent (R2). A missing CDI scope on the class is therefore
     * <em>not</em> enough; the producer must be actively {@linkplain ExcludedTypeBuildItem excluded} from
     * discovery when the {@code AGROAL} capability is absent. When it is present, the producer is registered
     * (and pinned unremovable, since the engine {@code ConnectionPoolService} that consumes its
     * {@code ConnectionPoolProvider} is itself injected into the RESTEasy-mediated {@code ConnectionPoolsResource},
     * which Arc's usage analysis cannot see). The always-produced {@code ConnectionPoolService} (see
     * {@link io.github.jdubois.bootui.quarkus.BootUiEngineProducer}) then receives a {@code null} provider when
     * absent and renders the panel unavailable, so it never fails — it is simply reported unavailable in the
     * manifest until a JDBC datasource extension is added.</p>
     *
     * <p>The {@code AGROAL} capability is present whenever a JDBC datasource extension (e.g.
     * {@code quarkus-jdbc-h2}) is on the classpath, independent of whether a datasource URL is actually
     * configured. The present-key therefore tracks Agroal availability; an application with Agroal present but
     * no datasource configured produces an empty pool list and the panel renders its empty state, matching the
     * Spring adapter (HikariCP on the classpath with zero pools).</p>
     */
    @BuildStep
    void registerConnectionPools(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.AGROAL);
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(AGROAL_PRODUCER_CLASS)
                    .setUnremovable()
                    .build());
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY, "true"));
        } else {
            // No JDBC datasource extension (or production): keep the io.agroal-importing producer out of bean
            // discovery so Arc never links the Agroal API. The connection-pool service still wires via the
            // always-produced ConnectionPoolService and renders the panel unavailable; the panel is reported
            // unavailable in the manifest (CONNECTION_POOLS_PRESENT_KEY defaults to false).
            excludedTypes.produce(new ExcludedTypeBuildItem(AGROAL_PRODUCER_CLASS));
        }
    }
}
