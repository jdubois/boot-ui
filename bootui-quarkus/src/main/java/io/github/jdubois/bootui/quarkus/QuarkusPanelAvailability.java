package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

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

    private static final String NOT_YET_AVAILABLE = "Not yet available on Quarkus.";

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

    public PanelsReport manifest() {
        return new PanelsReport(
                PanelsReport.PLATFORM_QUARKUS,
                BootUiPanels.all().stream().map(this::toDto).toList());
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = AVAILABLE_PANELS.contains(panel.id());
        String unavailableReason = available ? null : NOT_APPLICABLE.getOrDefault(panel.id(), NOT_YET_AVAILABLE);
        return new PanelDto(panel.id(), panel.title(), available, unavailableReason, true, false, null);
    }
}
