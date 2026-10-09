package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * High-level information about the running application.
 */
public record OverviewDto(
        String bootUiVersion,
        String applicationName,
        String frameworkName,
        String frameworkVersion,
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
        String openApiUrl,
        ApplicationRunDto run) {

    public OverviewDto {
        activeProfiles = DtoCollections.immutableCopy(activeProfiles);
        defaultProfiles = DtoCollections.immutableCopy(defaultProfiles);
    }

    /** Without the run identity. */
    public OverviewDto(
            String bootUiVersion,
            String applicationName,
            String frameworkName,
            String frameworkVersion,
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
            String openApiUrl) {
        this(
                bootUiVersion,
                applicationName,
                frameworkName,
                frameworkVersion,
                javaVersion,
                javaVendor,
                activeProfiles,
                defaultProfiles,
                webApplicationType,
                serverPort,
                managementPort,
                contextPath,
                startupTimeMillis,
                activation,
                openApiUrl,
                null);
    }
}
