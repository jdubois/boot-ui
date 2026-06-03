package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Health node, possibly nested.
 */
public record HealthNodeDto(
        String name,
        String status,
        Object details,
        List<HealthNodeDto> components,
        boolean available,
        String unavailableReason,
        String guidanceReason,
        List<HealthSetupStepDto> setup) {

    public HealthNodeDto(String name, String status, Object details, List<HealthNodeDto> components) {
        this(name, status, details, components, true, null, null, List.of());
    }
}
