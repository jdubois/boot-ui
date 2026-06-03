package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * High-level information about the running application.
 */
public record OverviewDto(
        String bootUiVersion,
        String applicationName,
        String springBootVersion,
        String javaVersion,
        String javaVendor,
        List<String> activeProfiles,
        List<String> defaultProfiles,
        String webApplicationType,
        Integer serverPort,
        Integer managementPort,
        String contextPath,
        Long startupTimeMillis,
        ActivationStatus activation,
        String openApiUrl) {}
