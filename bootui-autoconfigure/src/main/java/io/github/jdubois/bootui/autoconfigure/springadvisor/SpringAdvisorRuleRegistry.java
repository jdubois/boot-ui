package io.github.jdubois.bootui.autoconfigure.springadvisor;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated Spring Advisor rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class SpringAdvisorRuleRegistry {

    private static final List<SpringAdvisorRule> ACTIVE_RULES = List.of(
            // Bean wiring
            new BeanDefinitionOverridingRule(),
            new CircularReferencesAllowedRule(),
            new DuplicateObjectMapperRule(),
            new AmbiguousTaskExecutorRule(),
            new AmbiguousDataSourceRule(),
            // Configuration
            new LazyInitializationDisabledRule(),
            new DebugOrTraceLoggingRule(),
            // Profiles and environment
            new NoActiveProfileRule(),
            new DevToolsOnClasspathRule(),
            // Performance and concurrency
            new VirtualThreadsAvailableRule(),
            new VirtualThreadsOverriddenByPoolRule(),
            new AsyncWithoutCustomExecutorRule(),
            new ConnectionPoolSmallForVirtualThreadsRule(),
            // Web and HTTP
            new ResponseCompressionDisabledRule(),
            new GracefulShutdownDisabledRule(),
            new Http2DisabledRule());

    private SpringAdvisorRuleRegistry() {}

    static List<SpringAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
