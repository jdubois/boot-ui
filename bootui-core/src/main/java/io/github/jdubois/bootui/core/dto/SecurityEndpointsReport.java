package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Per-endpoint Spring Security authorization report.
 */
public record SecurityEndpointsReport(
        boolean springSecurityPresent,
        boolean handlerMappingAvailable,
        int total,
        List<SecurityEndpointDto> endpoints) {}
