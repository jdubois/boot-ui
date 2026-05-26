package io.github.bootui.core;

import java.util.List;
import java.util.Map;

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
            ActivationStatus activation,
            String openApiUrl) {
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

    /** A known configuration property that can be used for new overrides. */
    public record ConfigPropertySuggestionDto(
            String name,
            String type,
            String description,
            Object defaultValue) {
    }

    public record ConfigReport(
            List<String> activeProfiles,
            List<String> sources,
            List<ConfigPropertyDto> properties,
            List<ConfigPropertySuggestionDto> propertySuggestions) {
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

    /** Request from the browser to probe a local HTTP endpoint. */
    public record HttpProbeRequest(
            String method,
            String path,
            String body,
            Map<String, String> headers) {
    }

    /** Result of an HTTP probe. */
    public record HttpProbeResponse(
            int status,
            String statusText,
            Map<String, String> headers,
            String body,
            long durationMs,
            String error) {
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

    /** A single log line for the live log tail. */
    public record LogLineDto(long timestamp, String level, String logger, String message, String thread) {
    }

    /** DevTools-backed reload and restart status. */
    public record DevToolsStatus(
            boolean restartAvailable,
            String restartUnavailableReason,
            boolean restartPending,
            boolean liveReloadAvailable,
            Integer liveReloadPort,
            String liveReloadUnavailableReason) {
    }

    /** Request to restart the application through Spring Boot DevTools. */
    public record DevToolsRestartRequest(Boolean confirm) {
    }

    /** Result of a DevTools reload or restart action. */
    public record DevToolsActionResult(String action, String status, String message) {
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

    /** Summary of a @Scheduled task registered in the application context. */
    public record ScheduledTaskDto(
            String runnable,
            String triggerType,
            String expression,
            Long initialDelayMs,
            String timeUnit) {
    }

    public record ScheduledReport(boolean schedulingPresent, int total, List<ScheduledTaskDto> tasks) {
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

    /** Properties contributed by a single profile-specific property source. */
    public record ProfileSourceDto(
            String sourceName,
            String profile,
            List<ConfigPropertyDto> properties) {
    }

    /** Profile-aware view of the active configuration. */
    public record ProfilesReport(
            List<String> activeProfiles,
            List<ProfileSourceDto> profileSources) {
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

    /**
     * Authorization rule that applies to a single HTTP endpoint.
     *
     * <p>{@code rule} is one of:
     * <ul>
     *   <li>{@code permitAll} — anyone (authenticated or not) can access</li>
     *   <li>{@code denyAll} — nobody can access</li>
     *   <li>{@code authenticated} — any authenticated user can access</li>
     *   <li>{@code hasRole} — one of {@code roles} is required (already prefixed with {@code ROLE_})</li>
     *   <li>{@code hasAuthority} — one of {@code roles} (here used for arbitrary authorities) is required</li>
     *   <li>{@code unsecured} — no Spring Security filter chain matched the endpoint</li>
     *   <li>{@code custom} — a custom {@code AuthorizationManager} is in effect; see {@code description}</li>
     *   <li>{@code unknown} — no rule could be determined (no matching chain or no authorization filter)</li>
     * </ul>
     *
     * <p>{@code bestEffort} is {@code true} when the resolution relied on a stubbed request
     * that did not include headers/session state.</p>
     */
    public record SecurityEndpointDto(
            String method,
            String pattern,
            String handler,
            boolean secured,
            String rule,
            List<String> roles,
            Integer chainIndex,
            String matcherDescription,
            String description,
            boolean bestEffort) {
    }

    /** Per-endpoint Spring Security authorization report. */
    public record SecurityEndpointsReport(
            boolean springSecurityPresent,
            boolean handlerMappingAvailable,
            int total,
            List<SecurityEndpointDto> endpoints) {
    }

    /** One Micrometer meter exposed by the application's meter registry. */
    public record MetricMeterDto(
            String name,
            String description,
            String baseUnit,
            String type,
            List<MetricAvailableTagDto> availableTags) {
    }

    /** A concrete tag attached to a Micrometer meter sample. */
    public record MetricTagDto(String key, String value) {
    }

    /** Available values for one Micrometer meter tag key. */
    public record MetricAvailableTagDto(String key, List<String> values, boolean truncated) {
    }

    /** One measured statistic for a Micrometer meter. */
    public record MetricMeasurementDto(String statistic, double value) {
    }

    /** One concrete tagged Micrometer meter sample. */
    public record MetricSampleDto(List<MetricTagDto> tags, List<MetricMeasurementDto> measurements) {
    }

    /** Browseable list of Micrometer meters. */
    public record MetricsReport(boolean metricsAvailable, int total, List<MetricMeterDto> meters) {
    }

    /** Detail view for one Micrometer meter name, including current values. */
    public record MetricDetailDto(
            boolean metricsAvailable,
            String name,
            String description,
            String baseUnit,
            String type,
            List<MetricMeasurementDto> measurements,
            List<MetricAvailableTagDto> availableTags,
            List<MetricSampleDto> samples) {
    }

    /** A single JVM memory pool (heap, non-heap, or GC pool). */
    public record MemoryPoolDto(
            String name,
            long usedBytes,
            long committedBytes,
            long maxBytes,
            int usedPercent) {
    }

    /** Snapshot of JVM memory metrics. */
    public record MemoryReport(
            MemoryPoolDto heap,
            MemoryPoolDto nonHeap,
            List<MemoryPoolDto> pools,
            List<String> jvmInputArguments,
            String suggestedJvmOptions) {
    }

    /** A host/container port mapping exposed by a local development service. */
    public record DevServicePortDto(
            Integer containerPort,
            Integer hostPort,
            String protocol) {
    }

    /** One Docker Compose, Testcontainers, or service-connection entry. */
    public record DevServiceDto(
            String id,
            String name,
            String type,
            String source,
            String image,
            String status,
            String host,
            List<DevServicePortDto> ports,
            Map<String, Object> connectionDetails,
            boolean restartable,
            boolean logsAvailable,
            String note) {
    }

    /** Top-level report for local development services. */
    public record DevServicesReport(
            boolean dockerComposePresent,
            boolean testcontainersPresent,
            long snapshotTimestamp,
            int total,
            List<DevServiceDto> services) {
    }

    /** Tail of logs for one local development service. */
    public record DevServiceLogReport(
            String id,
            String logs,
            boolean truncated,
            int maxBytes) {
    }

    /** Result of restarting a local development service. */
    public record DevServiceRestartResult(
            String id,
            String status,
            String message) {
    }
}
