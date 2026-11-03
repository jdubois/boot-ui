package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadAnalysisReport(
        ThreadCaptureSummary capture,
        List<ThreadBlockerDto> rootBlockers,
        PageMetadata blockerPage,
        List<ThreadCycleDto> cycles,
        PageMetadata cyclePage,
        List<ThreadWaitDto> unresolvedWaits,
        PageMetadata unresolvedPage,
        List<ThreadStackGroupDto> stackGroups,
        PageMetadata groupPage) {
    public ThreadAnalysisReport {
        rootBlockers = DtoCollections.immutableCopy(rootBlockers);
        cycles = DtoCollections.immutableCopy(cycles);
        unresolvedWaits = DtoCollections.immutableCopy(unresolvedWaits);
        stackGroups = DtoCollections.immutableCopy(stackGroups);
    }
}
