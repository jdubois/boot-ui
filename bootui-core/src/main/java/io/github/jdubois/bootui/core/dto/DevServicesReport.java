package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for local development services.
 */
public record DevServicesReport(
        boolean dockerComposePresent,
        boolean testcontainersPresent,
        long snapshotTimestamp,
        int total,
        List<DevServiceDto> services,
        List<String> warnings) {

    public DevServicesReport(
            boolean dockerComposePresent,
            boolean testcontainersPresent,
            long snapshotTimestamp,
            int total,
            List<DevServiceDto> services) {
        this(dockerComposePresent, testcontainersPresent, snapshotTimestamp, total, services, List.of());
    }
}
