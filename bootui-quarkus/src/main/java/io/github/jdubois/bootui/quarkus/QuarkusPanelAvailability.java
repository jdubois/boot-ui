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
 * over Actuator/bean presence). This tracer-bullet release lights up only the framework-neutral Threads
 * and Heap Dump panels; every other panel is reported unavailable with a clear reason until its Quarkus
 * backing is ported. Read-only is not yet modelled, so no panel is read-only ({@code readOnlyReason}
 * stays {@code null}).</p>
 */
@ApplicationScoped
public class QuarkusPanelAvailability {

    private static final String NOT_YET_AVAILABLE = "Not yet available on Quarkus.";

    private static final Set<String> AVAILABLE_PANELS = Set.of(BootUiPanels.THREADS, BootUiPanels.HEAP_DUMP);

    public PanelsReport manifest() {
        return new PanelsReport(BootUiPanels.all().stream().map(this::toDto).toList());
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = AVAILABLE_PANELS.contains(panel.id());
        return new PanelDto(
                panel.id(), panel.title(), available, available ? null : NOT_YET_AVAILABLE, true, false, null);
    }
}
