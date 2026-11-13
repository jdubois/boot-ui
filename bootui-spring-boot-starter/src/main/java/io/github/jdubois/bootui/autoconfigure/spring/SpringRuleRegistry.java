package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.List;
import java.util.Set;

/**
 * Fixed, reviewable registry of the curated Spring Advisor rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class SpringRuleRegistry {

    /**
     * Rule IDs that were removed from the catalogue. They stay reserved so existing dismissals never silently apply
     * to a different check; see the "Retired rule IDs" section of docs/SPRING-CHECKS.md.
     */
    static final Set<String> RETIRED_RULE_IDS = Set.of(
            "SPRING-PROFILE-001",
            "SPRING-PERF-004",
            "SPRING-WEB-003",
            "SPRING-WEB-006",
            "SPRING-REACTIVE-001",
            "SPRING-REACTIVE-002",
            "SPRING-CONFIG-001");

    private static final List<SpringRule> ACTIVE_RULES = List.of(
            // Bean wiring
            new BeanDefinitionOverridingRule(),
            new CircularReferencesAllowedRule(),
            new DuplicateObjectMapperRule(),
            new AmbiguousTaskExecutorRule(),
            new AmbiguousDataSourceRule(),
            new AmbiguousTransactionManagerRule(),
            new RestTemplateInUseRule(),
            new DefaultPackageComponentsRule(),
            new MutableSingletonFieldRule(),
            new DormantResilienceAnnotationsRule(),
            new DormantInfrastructureAnnotationsRule(),
            // Configuration
            new DebugOrTraceLoggingRule(),
            new RemovedOrRenamedPropertyRule(),
            new MissingApplicationNameRule(),
            new ConfigOnNotFoundIgnoreRule(),
            new Jackson2DefaultsCompatibilityRule(),
            new Jackson2AutoConfigurationActiveRule(),
            new PropertiesMigratorPresentRule(),
            // Profiles and environment
            new DevToolsOnClasspathRule(),
            new ProfileValidationDisabledRule(),
            // Performance and concurrency
            new VirtualThreadsAvailableRule(),
            new VirtualThreadsOverriddenByPoolRule(),
            new AsyncWithoutCustomExecutorRule(),
            new SchedulerPoolTooSmallRule(),
            new UnboundedAsyncQueueRule(),
            new VirtualThreadsBeforeJep491Rule(),
            new VirtualThreadsExecutorBackoffRule(),
            new IgnoredVirtualThreadPoolPropertiesRule(),
            new IncompleteTaskShutdownTerminationRule(),
            new InMemoryCacheManagerRule(),
            // Web and HTTP
            new ResponseCompressionDisabledRule(),
            new GracefulShutdownDisabledRule(),
            new ErrorDetailsExposedRule(),
            new HttpClientTimeoutsUnsetRule(),
            new RedundantTomcatThreadsRule(),
            new UnlimitedMultipartRequestRule(),
            // Data and persistence
            new OpenSessionInViewEnabledRule(),
            new InMemoryDatasourceInProductionRule(),
            new InMemoryR2dbcInProductionRule(),
            // Actuator and management
            new ActuatorExposeAllRule(),
            new SensitiveActuatorEndpointsExposedRule(),
            new ActuatorShowValuesAlwaysRule(),
            new DangerousActuatorEndpointsAccessibleRule(),
            // Reactive (WebFlux only)
            new UnlimitedCodecAggregationRule());

    private SpringRuleRegistry() {}

    static List<SpringRule> activeRules() {
        return ACTIVE_RULES;
    }
}
