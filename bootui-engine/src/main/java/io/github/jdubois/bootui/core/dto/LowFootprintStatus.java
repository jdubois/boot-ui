package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The state of <b>low-footprint mode</b> ({@code GET} and {@code POST /bootui/api/live-memory/footprint}): while on,
 * BootUI has dropped what it buffers and paused its recording so the memory panels measure the application.
 *
 * @param active whether the mode is on; in memory only, so it is off again after a restart
 * @param sinceEpochMillis when the mode was turned on, or {@code null} while it is off
 * @param agentAttached whether the BootUI Java agent is attached; the agent is not paused by this mode
 * @param components every component the mode paused, or tried to, in a stable order; empty while the mode is off
 * @param warnings what the developer should know, such as an attached agent or a component that failed
 * @param offload the Free BootUI memory report of the request that turned the mode on, or {@code null} otherwise
 */
public record LowFootprintStatus(
        boolean active,
        Long sinceEpochMillis,
        boolean agentAttached,
        List<SuspendedComponentDto> components,
        List<String> warnings,
        MemoryOffloadReport offload) {

    public LowFootprintStatus {
        components = DtoCollections.immutableCopy(components);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
