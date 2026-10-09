package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record StartupReport(List<StartupStepDto> steps) {

    public StartupReport {
        steps = DtoCollections.immutableCopy(steps);
    }
}
