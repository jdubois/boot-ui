package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Computes the BootUI panel manifest for the Quarkus adapter.
 *
 * <p>The panel registry ({@link BootUiPanels}) is shared with the Spring adapter, so panel ids, titles
 * and order are identical and the shared Vue UI renders the same sidebar on both platforms. Availability
 * is platform-specific and computed here (the Spring adapter's {@code PanelsController} computes its own
 * over Actuator/bean presence). This release lights up the framework-neutral Threads, Heap Dump,
 * Live Memory, JVM Tuning and Loggers panels, the Health panel (always available: it reports real SmallRye
 * Health when {@code quarkus-smallrye-health} is present and otherwise renders setup guidance), plus the
 * OpenTelemetry-backed Traces and AI Usage panels (whose read services are always wired — they simply render
 * empty until spans are captured, which requires {@code quarkus-opentelemetry} on the application classpath);
 * every other panel is reported unavailable with a clear reason until its Quarkus backing is ported. Read-only
 * is not yet modelled, so no panel is read-only ({@code readOnlyReason} stays {@code null}) — note Traces (its
 * buffer can be cleared) and Loggers (a logger level can be set) are action-capable, so they are the Quarkus
 * panels exposing state-changing actions.</p>
 *
 * <p>Note the Overview <em>panel</em> stays unavailable here even though {@code GET /bootui/api/overview}
 * <em>is</em> served on Quarkus (by {@code OverviewResource}/{@code QuarkusApplicationInfo}): that
 * endpoint is the shared shell's framework-neutral chrome/CSRF-priming source, which the shell needs on
 * every platform, whereas the Overview dashboard panel itself has not yet been ported.</p>
 */
@ApplicationScoped
public class QuarkusPanelAvailability {

    private static final String NOT_YET_AVAILABLE = "Not yet available on Quarkus.";

    private static final Set<String> AVAILABLE_PANELS = Set.of(
            BootUiPanels.THREADS,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.LOGGERS,
            BootUiPanels.HEALTH,
            BootUiPanels.TRACES,
            BootUiPanels.AI);

    public PanelsReport manifest() {
        return new PanelsReport(
                PanelsReport.PLATFORM_QUARKUS,
                BootUiPanels.all().stream().map(this::toDto).toList());
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = AVAILABLE_PANELS.contains(panel.id());
        return new PanelDto(
                panel.id(), panel.title(), available, available ? null : NOT_YET_AVAILABLE, true, false, null);
    }
}
