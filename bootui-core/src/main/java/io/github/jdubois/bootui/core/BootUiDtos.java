package io.github.jdubois.bootui.core;

import java.util.List;
import java.util.Map;

/**
 * Lightweight DTOs returned by the BootUI internal API.
 *
 * <p>Records keep the surface immutable and JSON-serialization friendly with
 * the default Jackson configuration shipped by Spring Boot.</p>
 */
public final class BootUiDtos {

    private BootUiDtos() {}

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

    /**
     * Reason why BootUI activated, plus current safety settings.
     */
    public record ActivationStatus(boolean enabled, boolean localhostOnly, String reason, List<String> warnings) {}

    /**
     * Availability status for one BootUI sidebar panel.
     */
    public record PanelDto(
            String id,
            String title,
            boolean available,
            String unavailableReason,
            boolean enabled,
            boolean readOnly,
            String readOnlyReason) {

        public PanelDto(String id, String title, boolean available, String unavailableReason) {
            this(id, title, available, unavailableReason, true, false, null);
        }
    }

    public record PanelsReport(List<PanelDto> panels) {}

    /**
     * Spring-managed bean summary.
     */
    public record BeanSummary(
            String name,
            String type,
            String scope,
            String resource,
            List<String> dependencies,
            List<String> aliases,
            String classification) {}

    /**
     * Paging metadata for list-style reports.
     */
    public record PageMetadata(int total, int matched, int offset, int limit, int returned, boolean hasMore) {}

    public record BeanList(int total, List<BeanSummary> beans, PageMetadata page) {
        public BeanList(int total, List<BeanSummary> beans) {
            this(total, beans, new PageMetadata(total, beans.size(), 0, beans.size(), beans.size(), false));
        }
    }

    /**
     * One auto-configuration evaluation entry.
     */
    public record ConditionEntry(String autoConfigurationClass, String condition, String message, String outcome) {}

    public record ConditionCounts(
            int positiveTotal,
            int positiveMatched,
            int negativeTotal,
            int negativeMatched,
            int unconditionalTotal,
            int exclusionsTotal) {}

    public record ConditionsReport(
            List<ConditionEntry> positiveMatches,
            List<ConditionEntry> negativeMatches,
            List<String> unconditionalClasses,
            List<String> exclusions,
            PageMetadata page,
            ConditionCounts counts) {
        public ConditionsReport(
                List<ConditionEntry> positiveMatches,
                List<ConditionEntry> negativeMatches,
                List<String> unconditionalClasses,
                List<String> exclusions) {
            this(
                    positiveMatches,
                    negativeMatches,
                    unconditionalClasses,
                    exclusions,
                    new PageMetadata(
                            positiveMatches.size() + negativeMatches.size(),
                            positiveMatches.size() + negativeMatches.size(),
                            0,
                            positiveMatches.size() + negativeMatches.size(),
                            positiveMatches.size() + negativeMatches.size(),
                            false),
                    new ConditionCounts(
                            positiveMatches.size(),
                            positiveMatches.size(),
                            negativeMatches.size(),
                            negativeMatches.size(),
                            unconditionalClasses.size(),
                            exclusions.size()));
        }
    }

    /**
     * A single configuration property value, with its source.
     */
    public record ConfigPropertyDto(
            String name,
            Object value,
            String source,
            String origin,
            boolean masked,
            boolean override,
            String description,
            Object defaultValue) {}

    /**
     * A known configuration property that can be used for new overrides.
     */
    public record ConfigPropertySuggestionDto(String name, String type, String description, Object defaultValue) {}

    public record ConfigReport(
            List<String> activeProfiles,
            List<String> sources,
            List<ConfigPropertyDto> properties,
            List<ConfigPropertySuggestionDto> propertySuggestions,
            PageMetadata page,
            int overrideCount) {
        public ConfigReport(
                List<String> activeProfiles,
                List<String> sources,
                List<ConfigPropertyDto> properties,
                List<ConfigPropertySuggestionDto> propertySuggestions) {
            this(
                    activeProfiles,
                    sources,
                    properties,
                    propertySuggestions,
                    new PageMetadata(
                            properties.size(), properties.size(), 0, properties.size(), properties.size(), false),
                    (int) properties.stream()
                            .filter(ConfigPropertyDto::override)
                            .count());
        }
    }

    /**
     * Request to add/update a runtime property override.
     */
    public record ConfigOverrideRequest(String name, String value, Boolean persist) {}

    /**
     * Result of mutating a property override.
     */
    public record ConfigOverrideResult(
            String name, String value, String previousValue, boolean persisted, String message) {}

    /**
     * One HTTP mapping.
     */
    public record MappingDto(String method, String pattern, String handler, String produces, String consumes) {}

    public record MappingsReport(int total, List<MappingDto> mappings, PageMetadata page) {
        public MappingsReport(int total, List<MappingDto> mappings) {
            this(total, mappings, new PageMetadata(total, mappings.size(), 0, mappings.size(), mappings.size(), false));
        }
    }

    /**
     * Request from the browser to probe a local HTTP endpoint.
     */
    public record HttpProbeRequest(String method, String path, String body, Map<String, String> headers) {}

    /**
     * Result of an HTTP probe.
     */
    public record HttpProbeResponse(
            int status, String statusText, Map<String, String> headers, String body, long durationMs, String error) {}

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

    public record HealthSetupStepDto(String title, String description, List<String> snippets) {}

    public record LoggerDto(String name, String configuredLevel, String effectiveLevel) {}

    public record LoggersReport(List<String> availableLevels, List<LoggerDto> loggers, PageMetadata page) {
        public LoggersReport(List<String> availableLevels, List<LoggerDto> loggers) {
            this(
                    availableLevels,
                    loggers,
                    new PageMetadata(loggers.size(), loggers.size(), 0, loggers.size(), loggers.size(), false));
        }
    }

    /**
     * A single log line for the live log tail.
     */
    public record LogLineDto(long timestamp, String level, String logger, String message, String thread) {}

    /**
     * DevTools-backed reload and restart status.
     */
    public record DevToolsStatus(
            boolean restartAvailable,
            String restartUnavailableReason,
            boolean restartPending,
            boolean liveReloadAvailable,
            Integer liveReloadPort,
            String liveReloadUnavailableReason) {}

    /**
     * Request to restart the application through Spring Boot DevTools.
     */
    public record DevToolsRestartRequest(Boolean confirm) {}

    /**
     * Result of a DevTools reload or restart action.
     */
    public record DevToolsActionResult(String action, String status, String message) {}

    public record StartupStepDto(long id, Long parentId, String name, long durationMs, List<TagDto> tags) {}

    public record TagDto(String key, String value) {}

    public record StartupReport(List<StartupStepDto> steps) {}

    /**
     * Summary of a @Scheduled task registered in the application context.
     */
    public record ScheduledTaskDto(
            String runnable, String triggerType, String expression, Long initialDelayMs, String timeUnit) {}

    public record ScheduledReport(boolean schedulingPresent, int total, List<ScheduledTaskDto> tasks) {}

    /**
     * Summary of one Spring Data repository discovered in the context.
     */
    public record RepositoryDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            int queryMethodCount,
            int fragmentCount) {}

    /**
     * Detail view of a Spring Data repository, including its query methods.
     */
    public record RepositoryDetailDto(
            String beanName,
            String repositoryInterface,
            String domainType,
            String idType,
            String storeModule,
            String customImplementation,
            List<RepositoryMethodDto> methods,
            List<String> fragments) {}

    /**
     * One query method on a Spring Data repository.
     */
    public record RepositoryMethodDto(
            String name, String signature, String origin, String query, boolean nativeQuery, String namedQuery) {}

    public record RepositoriesReport(boolean springDataPresent, int total, List<RepositoryDto> repositories) {}

    /**
     * Current Micrometer cache metrics for one cache, when cache meters are registered.
     */
    public record CacheMetricsDto(
            boolean available,
            Double hits,
            Double misses,
            Double hitRatio,
            Double puts,
            Double evictions,
            Double removals,
            Double size) {}

    /**
     * One cache known to a Spring {@code CacheManager}.
     */
    public record CacheDto(String managerName, String name, String nativeType, Long size, CacheMetricsDto metrics) {}

    /**
     * One Spring {@code CacheManager} bean and its currently known caches.
     */
    public record CacheManagerDto(String name, String type, boolean noOp, List<CacheDto> caches) {}

    /**
     * One cache annotation operation discovered on an application bean method.
     */
    public record CacheOperationDto(
            String beanName,
            String targetType,
            String method,
            String operation,
            List<String> caches,
            String key,
            String condition,
            String unless,
            boolean allEntries,
            boolean beforeInvocation) {}

    /**
     * Top-level Spring Cache report.
     */
    public record CacheReport(
            boolean cacheAvailable,
            boolean clearEnabled,
            int managerCount,
            int cacheCount,
            int operationCount,
            List<CacheManagerDto> managers,
            List<CacheOperationDto> operations,
            List<String> warnings) {}

    /**
     * Live connection counts for one database connection pool at a point in time.
     */
    public record HikariPoolSnapshotDto(long timestamp, int active, int idle, int total, int pending) {}

    /**
     * One database connection pool bean, its (masked) connection metadata, sizing
     * and timeout settings, and the latest pool snapshot when reachable.
     */
    public record HikariPoolDto(
            String beanName,
            String poolName,
            String jdbcUrl,
            String username,
            String driverClassName,
            int minimumIdle,
            int maximumPoolSize,
            long connectionTimeoutMs,
            long idleTimeoutMs,
            long maxLifetimeMs,
            long validationTimeoutMs,
            long keepaliveTimeMs,
            boolean readOnly,
            boolean autoCommit,
            boolean available,
            String unavailableReason,
            HikariPoolSnapshotDto snapshot) {}

    /**
     * Top-level database connection-pool report.
     */
    public record HikariPoolsReport(boolean hikariPresent, int total, List<HikariPoolDto> pools) {}

    /**
     * Request to clear one cache or every known cache.
     */
    public record CacheClearRequest(String managerName, String cacheName, Boolean all, Boolean confirm) {}

    /**
     * Result of a cache clear operation.
     */
    public record CacheClearResult(String status, String message, int clearedCaches, List<String> caches) {}

    /**
     * Properties contributed by a single profile-specific property source.
     */
    public record ProfileSourceDto(String sourceName, String profile, List<ConfigPropertyDto> properties) {}

    /**
     * Profile-aware view of the active configuration.
     */
    public record ProfilesReport(List<String> activeProfiles, List<ProfileSourceDto> profileSources) {}

    /**
     * One Spring Security filter chain.
     */
    public record SecurityFilterChainDto(
            int order,
            String requestMatcher,
            String requestMatcherType,
            List<String> filters,
            boolean csrfEnabled,
            boolean corsEnabled,
            boolean sessionManagementPresent) {}

    /**
     * Authentication and user-details summary.
     */
    public record SecurityAuthDto(
            List<String> authenticationProviderTypes,
            List<String> userDetailsServiceTypes,
            String configuredUsername) {}

    /**
     * Result of the best-effort chain-matching explain.
     *
     * <p>{@code bestEffort} is {@code true} when the matching was performed
     * with limited request state and may be inaccurate for header- or
     * session-based matchers.</p>
     */
    public record SecurityExplainDto(
            boolean matched, boolean bestEffort, Integer chainIndex, String matcherDescription, List<String> filters) {}

    /**
     * Top-level Spring Security report.
     */
    public record SecurityReport(
            boolean springSecurityPresent, List<SecurityFilterChainDto> chains, SecurityAuthDto auth) {}

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
            boolean bestEffort) {}

    /**
     * Per-endpoint Spring Security authorization report.
     */
    public record SecurityEndpointsReport(
            boolean springSecurityPresent,
            boolean handlerMappingAvailable,
            int total,
            List<SecurityEndpointDto> endpoints) {}

    /**
     * Metadata about one local OWASP hygiene scan.
     */
    public record PentestScanStatusDto(
            String scanner, String status, String message, Long scannedAt, int checksRun, int findingsFound) {}

    /**
     * Count of pentest findings by normalized severity.
     */
    public record PentestSeverityCountDto(String severity, int count) {}

    /**
     * One OWASP category covered by the local scan.
     */
    public record PentestCoverageDto(String category, String title, String status, String description) {}

    /**
     * One heuristic finding from the local OWASP hygiene scan.
     */
    public record PentestFindingDto(
            String id,
            String title,
            String owaspCategory,
            String severity,
            String confidence,
            String target,
            String evidence,
            String recommendation,
            boolean heuristic) {}

    /**
     * Top-level report for the local pentesting / OWASP hygiene panel.
     */
    public record PentestReport(
            boolean localOnly,
            String disclaimer,
            int checksRun,
            int findingsFound,
            List<PentestSeverityCountDto> severityCounts,
            PentestScanStatusDto scan,
            List<PentestCoverageDto> coverage,
            List<PentestFindingDto> findings) {}

    /**
     * Metadata about one local ArchUnit architecture analysis run.
     */
    public record ArchitectureScanStatusDto(
            String analyzer,
            String status,
            String message,
            Long scannedAt,
            int rulesEvaluated,
            int classesAnalyzed,
            int violationsFound) {}

    /**
     * Count of architecture rule violations by normalized severity.
     */
    public record ArchitectureSeverityCountDto(String severity, int count) {}

    /**
     * Outcome of one architecture rule violation evaluated against the host application classes.
     */
    public record ArchitectureRuleResultDto(
            String id,
            String name,
            String category,
            String severity,
            String description,
            String status,
            int violationCount,
            List<String> sampleViolations,
            String recommendation) {}

    /**
     * Top-level report for the local architecture (ArchUnit) hygiene panel. The results list contains
     * violating rules only, ordered by severity and impact.
     */
    public record ArchitectureReport(
            boolean localOnly,
            String disclaimer,
            List<String> basePackages,
            int classesAnalyzed,
            int rulesEvaluated,
            int violationsFound,
            List<ArchitectureSeverityCountDto> severityCounts,
            ArchitectureScanStatusDto scan,
            List<ArchitectureRuleResultDto> results) {}

    /**
     * One Micrometer meter exposed by the application's meter registry.
     */
    public record MetricMeterDto(
            String name, String description, String baseUnit, String type, List<MetricAvailableTagDto> availableTags) {}

    /**
     * A concrete tag attached to a Micrometer meter sample.
     */
    public record MetricTagDto(String key, String value) {}

    /**
     * Available values for one Micrometer meter tag key.
     */
    public record MetricAvailableTagDto(String key, List<String> values, boolean truncated) {}

    /**
     * One measured statistic for a Micrometer meter.
     */
    public record MetricMeasurementDto(String statistic, double value) {}

    /**
     * One concrete tagged Micrometer meter sample.
     */
    public record MetricSampleDto(List<MetricTagDto> tags, List<MetricMeasurementDto> measurements) {}

    /**
     * Browseable list of Micrometer meters.
     */
    public record MetricsReport(boolean metricsAvailable, int total, List<MetricMeterDto> meters) {}

    /**
     * Detail view for one Micrometer meter name, including current values.
     */
    public record MetricDetailDto(
            boolean metricsAvailable,
            String name,
            String description,
            String baseUnit,
            String type,
            List<MetricMeasurementDto> measurements,
            List<MetricAvailableTagDto> availableTags,
            List<MetricSampleDto> samples) {}

    /**
     * One Maven dependency discovered on the running application's classpath.
     */
    public record DependencyDto(
            String groupId,
            String artifactId,
            String version,
            String packageName,
            String source,
            int vulnerabilityCount,
            String highestSeverity,
            List<DependencyVulnerabilityDto> vulnerabilities) {}

    /**
     * One vulnerability advisory affecting a dependency.
     */
    public record DependencyVulnerabilityDto(
            String id,
            String summary,
            String details,
            String severity,
            Double score,
            List<String> aliases,
            List<String> references,
            List<String> fixedVersions) {}

    /**
     * Count of vulnerability advisories by normalized severity.
     */
    public record DependencySeverityCountDto(String severity, int count) {}

    /**
     * Metadata about the dependency vulnerability scan.
     */
    public record DependencyScanStatusDto(
            String scanner,
            String status,
            String message,
            Long scannedAt,
            int packagesScanned,
            int vulnerabilitiesFound) {}

    /**
     * Top-level report for dependency inventory and vulnerability findings.
     */
    public record DependenciesReport(
            boolean scanningEnabled,
            int total,
            int vulnerable,
            List<DependencySeverityCountDto> severityCounts,
            DependencyScanStatusDto scan,
            List<DependencyDto> dependencies) {

        public String status() {
            return scan == null ? null : scan.status();
        }
    }

    /**
     * A single JVM memory pool (heap, non-heap, or GC pool).
     */
    public record MemoryPoolDto(String name, long usedBytes, long committedBytes, long maxBytes, int usedPercent) {}

    /**
     * Snapshot of JVM memory metrics.
     */
    public record MemoryReport(
            MemoryPoolDto heap,
            MemoryPoolDto nonHeap,
            List<MemoryPoolDto> pools,
            List<String> jvmInputArguments,
            String suggestedJvmOptions,
            MemoryCalculationDto calculation,
            KubernetesMemoryRecommendationDto kubernetes) {}

    /**
     * Result of the Paketo-style memory calculator.
     *
     * <p>Computed by partitioning a target container memory budget into JVM regions:
     * {@code heap = totalMemory − headRoom − directMemory − metaspace − codeCache − stack×threads}.
     * Mirrors the formula in {@code paketo-buildpacks/libjvm/calc/calculator.go} with one
     * adaptation: we use the live loaded-class count from {@link java.lang.management.ClassLoadingMXBean}
     * with a safety factor instead of the buildpack's build-time JAR-entry estimate.
     */
    public record MemoryCalculationDto(
            long totalMemoryBytes,
            long heapBytes,
            long metaspaceBytes,
            long codeCacheBytes,
            long directMemoryBytes,
            long stackBytesPerThread,
            long stackBytesTotal,
            long headRoomBytes,
            long fixedRegionsBytes,
            int threadCount,
            int loadedClasses,
            int liveThreadCount,
            int liveLoadedClassCount,
            int headRoomPercent,
            boolean virtualThreadsEnabled,
            String jvmOptions,
            boolean valid,
            String error) {}

    /**
     * Kubernetes resource recommendation derived from the JVM memory
     * calculator and the current runtime snapshot.
     */
    public record KubernetesMemoryRecommendationDto(
            long requestMemoryBytes,
            long limitMemoryBytes,
            long burstableRequestMemoryBytes,
            long currentSnapshotBytes,
            Long detectedContainerLimitBytes,
            String requestMemory,
            String limitMemory,
            String burstableRequestMemory,
            String currentSnapshotMemory,
            String detectedContainerLimitMemory,
            String qosClass,
            String confidence,
            List<String> warnings,
            String yaml,
            double maxRamPercentage,
            double initialRamPercentage,
            String javaToolOptions,
            boolean burstableEnabled,
            boolean actuatorProbesEnabled) {}

    /**
     * One captured heap dump file on local disk.
     */
    public record HeapDumpFileDto(String name, long sizeBytes, long createdAtEpochMs, boolean live) {}

    /**
     * One class entry from a JVM class histogram, sorted by retained bytes.
     *
     * <p>Only class names and aggregate sizes are exposed; object field values are never
     * read, so secrets held in live objects are not disclosed by this view.</p>
     */
    public record HeapClassHistogramEntryDto(int rank, String className, long instances, long bytes) {}

    /**
     * Metadata about the most recent heap dump capture or live-heap analysis action.
     */
    public record HeapDumpCaptureStatusDto(String status, String message, Long capturedAtEpochMs) {}

    /**
     * Top-level report for the Heap Dump diagnostics panel.
     *
     * <p>The class histogram is computed only by explicit capture/analyze actions (each of
     * which triggers a full GC); passive reads return the last computed histogram.</p>
     */
    public record HeapDumpReport(
            boolean hotspotAvailable,
            boolean captureEnabled,
            boolean rawDownloadEnabled,
            String outputDirectory,
            int maxDumps,
            int dumpCount,
            long liveHeapUsedBytes,
            long freeDiskBytes,
            HeapDumpCaptureStatusDto capture,
            List<HeapDumpFileDto> dumps,
            long histogramTotalInstances,
            long histogramTotalBytes,
            List<HeapClassHistogramEntryDto> topClasses) {}

    /**
     * A host/container port mapping exposed by a local development service.
     */
    public record DevServicePortDto(Integer containerPort, Integer hostPort, String protocol) {}

    /**
     * One Docker Compose, Testcontainers, or service-connection entry.
     */
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
            String note) {}

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

    /**
     * Tail of logs for one local development service.
     */
    public record DevServiceLogReport(String id, String logs, boolean truncated, int maxBytes) {}

    /**
     * Result of restarting a local development service.
     */
    public record DevServiceRestartResult(String id, String status, String message) {}

    // ----- OTLP traces + AI panel ----------------------------------------------------------

    /**
     * Summary describing a span attribute, normalized to a JSON-friendly value type.
     */
    public record SpanAttributeDto(String key, String type, Object value) {}

    /**
     * Discrete event recorded inside a span.
     */
    public record SpanEventDto(String name, long timeOffsetNanos, List<SpanAttributeDto> attributes) {}

    /**
     * Detailed span record returned by the Traces detail endpoint.
     */
    public record SpanDto(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            String serviceName,
            String scope,
            long startEpochNanos,
            long endEpochNanos,
            long durationNanos,
            String statusCode,
            String statusMessage,
            List<SpanAttributeDto> attributes,
            List<SpanEventDto> events) {}

    /**
     * Summary used by the Traces list view.
     */
    public record TraceSummaryDto(
            String traceId,
            String rootSpanName,
            List<String> services,
            long startEpochNanos,
            long endEpochNanos,
            long durationNanos,
            int spanCount,
            boolean hasError,
            boolean hasAi) {}

    /**
     * Top-level Traces list payload.
     */
    public record TracesReport(boolean enabled, int retained, int capacity, List<TraceSummaryDto> traces) {}

    /**
     * Top-level Trace detail payload.
     */
    public record TraceDetailDto(String traceId, List<SpanDto> spans) {}

    /**
     * Summary of a single AI chat completion span.
     */
    public record AiChatSummaryDto(
            String traceId,
            String spanId,
            long startEpochNanos,
            long durationNanos,
            String provider,
            String requestModel,
            String responseModel,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            String finishReason,
            String statusCode,
            String operation,
            int toolCallCount,
            int vectorOperationCount) {}

    /**
     * Tool call (function call) emitted by Spring AI advisors.
     */
    public record AiToolCallDto(
            String spanId, String name, long startEpochNanos, long durationNanos, String statusCode) {}

    /**
     * Vector store operation linked to the same trace.
     */
    public record AiVectorOpDto(
            String spanId,
            String operation,
            String collectionName,
            long startEpochNanos,
            long durationNanos,
            String statusCode) {}

    /**
     * Detail of a single AI chat span, including linked tool calls and vector operations from the same trace.
     */
    public record AiChatDetailDto(
            AiChatSummaryDto summary,
            List<AiToolCallDto> toolCalls,
            List<AiVectorOpDto> vectorOperations,
            List<SpanAttributeDto> attributes,
            List<SpanEventDto> events,
            boolean contentCaptured,
            String contentBanner) {}

    /**
     * Per-minute token usage bucket.
     */
    public record AiTokenBucketDto(long epochMinute, long inputTokens, long outputTokens, int callCount) {}

    /**
     * Token usage time series payload.
     */
    public record AiTokenSeriesDto(int minutes, List<AiTokenBucketDto> buckets) {}

    /**
     * AI Usage overview payload.
     */
    public record AiOverviewDto(
            boolean enabled,
            boolean springAiDetected,
            boolean langChain4jDetected,
            int totalChats,
            long totalInputTokens,
            long totalOutputTokens,
            Map<String, Long> tokensByModel,
            Map<String, Integer> callsByModel,
            int toolCallCount,
            int vectorOperationCount,
            int embeddingCount,
            List<AiChatSummaryDto> recent,
            String contentBanner) {}

    // ── Copilot panel ─────────────────────────────────────────────────────────

    /**
     * Sanitized summary of a single Copilot CLI session.
     */
    public record CopilotSessionSummary(
            String id,
            String filename,
            Long startedAtEpochMillis,
            Long updatedAtEpochMillis,
            String model,
            String workingDirectory,
            String status,
            int eventCount,
            int turnCount,
            int errorCount,
            String lastActivitySummary,
            boolean schemaDrift) {}

    /**
     * A single sanitized activity event observed in a Copilot session.
     *
     * <p>Only allowlisted fields are returned. Raw arguments, command output, file diffs,
     * and prompts are deliberately excluded from this DTO. The opt-in raw endpoint
     * exposes the source JSON locally on demand.</p>
     */
    public record CopilotActivityEvent(
            String id,
            int turnIndex,
            Long timestampEpochMillis,
            String type,
            String toolName,
            String category,
            String summary,
            Boolean success) {}

    /**
     * One turn of activity in a Copilot session.
     */
    public record CopilotTurn(
            int index, Long startedAtEpochMillis, Long durationMillis, String summary, int eventCount) {}

    /**
     * Aggregate counters across a Copilot session's events.
     */
    public record CopilotInsightCounts(
            int total, Map<String, Integer> byCategory, int errors, Long lastActivityEpochMillis) {}

    /**
     * Counted dashboard metric sorted by the backend before serialization.
     */
    public record CopilotMetricCount(String label, int count) {}

    /**
     * Time bucket for the Copilot dashboard activity chart.
     */
    public record CopilotActivityBucket(Long startEpochMillis, Long endEpochMillis, int eventCount, int errorCount) {}

    /**
     * Aggregated Copilot dashboard payload. Counts are computed from sanitized
     * session metadata only; raw prompts, arguments, output, and diffs are never
     * included.
     */
    public record CopilotDashboardDto(
            boolean available,
            String unavailableReason,
            String sessionStateDir,
            int sessionCount,
            int eventCount,
            int turnCount,
            int errorCount,
            int activeLast24Hours,
            int activeLast7Days,
            int sessionsWithSchemaDrift,
            Long lastActivityEpochMillis,
            List<CopilotMetricCount> categoryCounts,
            List<CopilotMetricCount> modelCounts,
            List<CopilotMetricCount> topTools,
            int otherToolEventCount,
            List<CopilotActivityBucket> activityBuckets,
            List<CopilotActivityBucket> dailyActivityBuckets,
            List<CopilotSessionSummary> recentSessions,
            List<String> warnings) {}

    /**
     * Detailed view of a Copilot session: summary, counts, turn story, and recent events.
     */
    public record CopilotSessionDetail(
            CopilotSessionSummary summary,
            CopilotInsightCounts counts,
            List<CopilotTurn> turns,
            List<CopilotActivityEvent> recentEvents,
            List<CopilotActivityEvent> failureEvents,
            List<String> warnings) {}

    /**
     * Response payload for the sessions list endpoint.
     */
    public record CopilotSessionListDto(
            boolean available,
            String unavailableReason,
            String sessionStateDir,
            int total,
            int returned,
            int maxSessions,
            List<CopilotSessionSummary> sessions,
            List<String> warnings) {}

    /**
     * Paginated/filtered events for a single session.
     */
    public record CopilotEventListDto(String sessionId, int total, int returned, List<CopilotActivityEvent> events) {}

    /**
     * Raw JSON details for a single event. Returned only when explicitly requested
     * and only when {@code bootui.copilot.allow-raw-reveal=true}.
     */
    public record CopilotRawEventDto(String sessionId, String eventId, String json) {}
}
