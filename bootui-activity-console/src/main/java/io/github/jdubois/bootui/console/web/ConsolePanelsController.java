package io.github.jdubois.bootui.console.web;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /bootui/api/panels}: reuses the full shared {@link BootUiPanels} registry (so the sidebar
 * renders every panel title exactly like every other adapter) but marks every panel other than {@link
 * BootUiPanels#ACTIVITY} unavailable with an explanatory reason. The Vue shell does not hide unavailable
 * panels outright &mdash; it moves them into a collapsed "Disabled / unavailable" sidebar section &mdash;
 * so Live Activity is the only panel shown prominently, matching the console's single-purpose design.
 */
@RestController
@RequestMapping("/bootui/api/panels")
public class ConsolePanelsController {

    static final String UNAVAILABLE_REASON =
            "Not applicable on the BootUI Activity Console, a single-purpose Live Activity aggregator.";

    @GetMapping
    public PanelsReport panels() {
        List<PanelDto> panels = BootUiPanels.all().stream().map(this::toDto).toList();
        return new PanelsReport(PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE, panels);
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean isActivity = BootUiPanels.ACTIVITY.equals(panel.id());
        return new PanelDto(
                panel.id(), panel.title(), isActivity, isActivity ? null : UNAVAILABLE_REASON, true, false, null);
    }
}
