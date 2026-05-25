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

    /** Summary of one Spring Data repository discovered in the context. */
    public record RepositoryDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            int queryMethodCount,
            int fragmentCount) {
    }

    /** Detail view of a Spring Data repository, including its query methods. */
    public record RepositoryDetailDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            List<RepositoryMethodDto> methods,
            List<String> fragments) {
    }

    /** One query method on a Spring Data repository. */
    public record RepositoryMethodDto(
            String name,
            String signature,
            String origin,
            String query,
            boolean nativeQuery,
            String namedQuery) {
    }

    public record RepositoriesReport(
            boolean springDataPresent,
            int total,
            List<RepositoryDto> repositories) {
    }

    /** One Spring Security filter chain. */
    public record SecurityFilterChainDto(
            int order,
            String requestMatcher,
            String requestMatcherType,
            List<String> filters,
            boolean csrfEnabled,
            boolean corsEnabled,
            boolean sessionManagementPresent) {
    }

    /** Authentication and user-details summary. */
    public record SecurityAuthDto(
            List<String> authenticationProviderTypes,
            List<String> userDetailsServiceTypes,
            String configuredUsername) {
    }

    /**
     * Result of the best-effort chain-matching explain.
     *
     * <p>{@code bestEffort} is {@code true} when the matching was performed
     * with limited request state and may be inaccurate for header- or
     * session-based matchers.</p>
     */
    public record SecurityExplainDto(
            boolean matched,
            boolean bestEffort,
            Integer chainIndex,
            String matcherDescription,
            List<String> filters) {
    }

    /** Top-level Spring Security report. */
    public record SecurityReport(
            boolean springSecurityPresent,
            List<SecurityFilterChainDto> chains,
            SecurityAuthDto auth) {
    }
}
