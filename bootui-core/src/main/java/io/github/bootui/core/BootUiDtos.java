package io.github.bootui.core;

import java.util.List;

/**
 * Lightweight DTOs returned by the BootUI internal API.
 *
 * <p>Records keep the surface immutable and JSON-serialization friendly with
 * the default Jackson configuration shipped by Spring Boot.</p>
 */
public final class BootUiDtos {

    private BootUiDtos() {
    }

    /** High-level information about the running application. */
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
            ActivationStatus activation) {
    }

    /** Reason why BootUI activated, plus current safety settings. */
    public record ActivationStatus(
            boolean enabled,
            boolean localhostOnly,
            String reason,
            List<String> warnings) {
    }

    /** Spring-managed bean summary. */
    public record BeanSummary(
            String name,
            String type,
            String scope,
            String resource,
            List<String> dependencies,
            List<String> aliases,
            String classification) {
    }

    public record BeanList(int total, List<BeanSummary> beans) {
    }

    /** One auto-configuration evaluation entry. */
    public record ConditionEntry(
            String autoConfigurationClass,
            String condition,
            String message,
            String outcome) {
    }

    public record ConditionsReport(
            List<ConditionEntry> positiveMatches,
            List<ConditionEntry> negativeMatches,
            List<String> unconditionalClasses,
            List<String> exclusions) {
    }

    /** A single configuration property value, with its source. */
    public record ConfigPropertyDto(
            String name,
            Object value,
            String source,
            String origin,
            boolean masked,
            boolean override,
            String description,
            Object defaultValue) {
    }

    public record ConfigReport(
            List<String> activeProfiles,
            List<String> sources,
            List<ConfigPropertyDto> properties) {
    }

    /** Request to add/update a runtime property override. */
    public record ConfigOverrideRequest(String name, String value, Boolean persist) {
    }

    /** Result of mutating a property override. */
    public record ConfigOverrideResult(
            String name,
            String value,
            String previousValue,
            boolean persisted,
            String message) {
    }

    /** One HTTP mapping. */
    public record MappingDto(
            String method,
            String pattern,
            String handler,
            String produces,
            String consumes) {
    }

    public record MappingsReport(int total, List<MappingDto> mappings) {
    }

    /** Health node, possibly nested. */
    public record HealthNodeDto(
            String name,
            String status,
            Object details,
            List<HealthNodeDto> components) {
    }

    public record LoggerDto(
            String name,
            String configuredLevel,
            String effectiveLevel) {
    }

    public record LoggersReport(List<String> availableLevels, List<LoggerDto> loggers) {
    }

    public record StartupStepDto(
            long id,
            Long parentId,
            String name,
            long durationMs,
            List<TagDto> tags) {
    }

    public record TagDto(String key, String value) {
    }

    public record StartupReport(List<StartupStepDto> steps) {
    }
}
