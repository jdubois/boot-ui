package io.github.jdubois.bootui.autoconfigure.architecture;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated architecture rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class ArchitectureRuleRegistry {

    private static final List<ArchitectureRule> ACTIVE_RULES = List.of(
            new FreeOfPackageCyclesRule(),
            new NoStandardStreamsRule(),
            new NoGenericExceptionsRule(),
            new NoJavaUtilLoggingRule(),
            new NoJodaTimeRule(),
            new NoFieldInjectionRule(),
            new ControllersShouldNotDependOnRepositoriesRule(),
            new RepositoriesShouldNotDependOnControllersRule(),
            new ControllerNamingRule());

    private ArchitectureRuleRegistry() {}

    static List<ArchitectureRule> activeRules() {
        return ACTIVE_RULES;
    }
}
