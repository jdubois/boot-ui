package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;

/**
 * Computes the BootUI panel manifest for the Quarkus adapter.
 *
 * <p>The panel registry ({@link BootUiPanels}) is shared with the Spring adapter, so panel ids, titles
 * and order are identical and the shared Vue UI renders the same sidebar on both platforms. Availability
 * is platform-specific and computed here (the Spring adapter's {@code PanelsController} computes its own
 * over Actuator/bean presence). This release lights up the framework-neutral Threads, Heap Dump,
 * Live Memory, JVM Tuning and Loggers panels, the Metrics panel (which reports real Micrometer meters when a
 * {@code quarkus-micrometer} registry is present and otherwise renders as unavailable), the HTTP Probe panel
 * (which probes the application's own loopback port), the Health panel (always available: it reports real
 * SmallRye Health when {@code quarkus-smallrye-health} is present and otherwise renders setup guidance), the
 * Architecture (ArchUnit) advisor (which bounds its bytecode import to the application base packages discovered
 * from the build-time Jandex index and runs the shared curated rule registry on demand), plus the
 * OpenTelemetry-backed Traces and AI Usage panels (whose read services are always wired — they simply
 * render empty until spans are captured, which requires {@code quarkus-opentelemetry} on the application
 * classpath); every other panel is reported unavailable with a clear reason until its Quarkus backing is
 * ported. Read-only is not yet modelled, so no panel is read-only ({@code readOnlyReason} stays {@code null})
 * — note Traces (its buffer can be cleared), Loggers (a logger level can be set), HTTP Probe (it issues a
 * request) and Architecture (it runs a scan and dismisses rules) are action-capable, so they are the Quarkus
 * panels exposing state-changing actions.</p>
 *
 * <p>The <strong>Hibernate</strong> (ORM mapping) advisor is available <em>dynamically</em>: unlike the
 * statically-available panels above, it is lit up only when the application actually uses Hibernate ORM. The
 * deployment processor detects the {@code HIBERNATE_ORM} capability at build time and feeds the decision back
 * as the {@code bootui.internal.hibernate-present} runtime-config default (see
 * {@link #HIBERNATE_PRESENT_KEY}); when Hibernate ORM is absent the panel reports an honest capability hint
 * rather than the generic "not yet ported" reason. The engine scanner and its {@code GET}/{@code POST /scan}
 * resource are always wired regardless — only the optional {@code jakarta.persistence}-reading entity
 * discovery is capability-gated (R2) — so the panel renders a not-configured report rather than failing when
 * the extension is absent.</p>
 *
 * <p>Note the Overview <em>panel</em> stays unavailable here even though {@code GET /bootui/api/overview}
 * <em>is</em> served on Quarkus (by {@code OverviewResource}/{@code QuarkusApplicationInfo}): that
 * endpoint is the shared shell's framework-neutral chrome/CSRF-priming source, which the shell needs on
 * every platform, whereas the Overview dashboard panel itself has not yet been ported.</p>
 *
 * <p>The Mappings panel is a deliberate, possibly permanent, exception rather than a not-yet-ported one:
 * unlike the Spring adapter (which flattens Actuator's {@code MappingsEndpoint} descriptor), Quarkus
 * exposes no clean <em>runtime</em> route-enumeration API. Vert.x {@code Router.getRoutes()} yields paths
 * but not the per-route method/produces/consumes the {@code MappingDto} contract needs, and RESTEasy
 * Reactive's resource model is a build-time artifact
 * ({@code ResteasyReactiveResourceMethodEntriesBuildItem}). Capturing it would require a new build-step +
 * {@code @Recorder} + {@code SyntheticBeanBuildItem} data-capture pattern (its own slice and critic
 * round); until then Mappings stays unavailable on Quarkus while remaining fully available on Spring.</p>
 *
 * <p>The <strong>GraalVM</strong> and <strong>CRaC</strong> advisors are deliberate, permanent exceptions
 * (see {@link #NOT_APPLICABLE}): they have no meaningful Quarkus equivalent rather than simply not being
 * ported yet. GraalVM native-image readiness is a Spring-specific concern — Quarkus compiles native images
 * itself and generates its own reachability metadata at build time, so the Spring-oriented advisor and its
 * {@code reachability-metadata.json} / {@code Dockerfile-native} scaffolding do not apply. CRaC targets the
 * Spring Boot startup model ({@code spring.context.checkpoint=onRefresh}); Quarkus's fast startup comes from
 * build-time augmentation and native images instead. Both therefore report an honest, panel-specific reason
 * so the shared Vue unavailable-alert never implies a port is forthcoming.</p>
 */
@ApplicationScoped
public class QuarkusPanelAvailability {

    /**
     * Runtime-config key carrying the build-time {@code HIBERNATE_ORM} capability decision. The deployment
     * processor emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}); this bean
     * reads it back to decide whether the dynamically-available Hibernate advisor panel is lit up. Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@code QUARKUS_VERSION_KEY} /
     * {@code BASE_PACKAGES_KEY}.
     */
    public static final String HIBERNATE_PRESENT_KEY = "bootui.internal.hibernate-present";

    private static final String NOT_YET_AVAILABLE = "Not yet available on Quarkus.";

    private static final String HIBERNATE_ABSENT =
            "Not available: this application does not use Hibernate ORM. Add the quarkus-hibernate-orm"
                    + " extension to enable the JPA mapping advisor.";

    /**
     * Panels that are deliberately and permanently unavailable on Quarkus because they have no meaningful
     * Quarkus equivalent — as opposed to the generic {@link #NOT_YET_AVAILABLE} panels that simply have not
     * been ported yet. Each maps to an honest, panel-specific reason.
     */
    private static final Map<String, String> NOT_APPLICABLE = Map.of(
            BootUiPanels.GRAALVM,
            "Not applicable on Quarkus: Quarkus builds native images and generates its own reachability"
                    + " metadata at build time, so this Spring-oriented native-image readiness advisor is not"
                    + " used here.",
            BootUiPanels.CRAC,
            "Not applicable on Quarkus: this CRaC checkpoint/restore advisor targets the Spring Boot startup"
                    + " model, and Quarkus's fast startup comes from build-time augmentation and native images"
                    + " instead, so it is not used here.");

    private static final Set<String> AVAILABLE_PANELS = Set.of(
            BootUiPanels.THREADS,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.METRICS,
            BootUiPanels.LOGGERS,
            BootUiPanels.HEALTH,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.ARCHITECTURE,
            BootUiPanels.TRACES,
            BootUiPanels.AI);

    private final boolean hibernatePresent;

    private final List<String> githubAllowedApiHosts;

    @Inject
    public QuarkusPanelAvailability(Config config) {
        this.hibernatePresent =
                config.getOptionalValue(HIBERNATE_PRESENT_KEY, Boolean.class).orElse(false);
        this.githubAllowedApiHosts = BootUiEngineProducer.gitHubAllowedApiHosts(config);
    }

    public PanelsReport manifest() {
        return new PanelsReport(
                PanelsReport.PLATFORM_QUARKUS,
                BootUiPanels.all().stream().map(this::toDto).toList());
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = AVAILABLE_PANELS.contains(panel.id())
                || (BootUiPanels.HIBERNATE.equals(panel.id()) && hibernatePresent)
                || (BootUiPanels.GITHUB.equals(panel.id()) && githubAvailable());
        String unavailableReason = available ? null : unavailableReason(panel.id());
        return new PanelDto(panel.id(), panel.title(), available, unavailableReason, true, false, null);
    }

    private String unavailableReason(String panelId) {
        if (BootUiPanels.HIBERNATE.equals(panelId)) {
            return HIBERNATE_ABSENT;
        }
        if (BootUiPanels.GITHUB.equals(panelId)) {
            return githubUnavailableReason();
        }
        return NOT_APPLICABLE.getOrDefault(panelId, NOT_YET_AVAILABLE);
    }

    /**
     * GitHub panel availability is <em>dynamic</em>, mirroring the Spring adapter's
     * {@code PanelsController.githubAvailable()}: the panel is available only when the host application's
     * working directory is a git checkout with a GitHub-origin remote on an allow-listed API host. Uses the
     * shared engine {@link GitHubRepositoryDetector}, so both adapters light up the panel under identical
     * conditions. Detection is local-only (reads the git config / filesystem) and never calls the network.
     */
    private boolean githubAvailable() {
        return GitHubRepositoryDetector.detect(githubWorkingDirectory(), githubAllowedApiHosts)
                .isPresent();
    }

    private String githubUnavailableReason() {
        return GitHubRepositoryDetector.unavailableReason(githubWorkingDirectory(), githubAllowedApiHosts);
    }

    private static Path githubWorkingDirectory() {
        return Path.of(System.getProperty("user.dir", "."));
    }
}
