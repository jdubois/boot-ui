package io.github.jdubois.bootui.autoconfigure.restadvisor;

/**
 * Immutable description of one curated REST API Advisor rule. The definition is independent of the
 * application under analysis; the matching {@link RestApiAdvisorRule} decides whether the rule
 * applies and produces an outcome.
 */
record RestApiAdvisorRuleDefinition(
        String id,
        String name,
        RestApiAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
