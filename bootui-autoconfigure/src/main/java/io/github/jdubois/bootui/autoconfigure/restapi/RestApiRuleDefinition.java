package io.github.jdubois.bootui.autoconfigure.restapi;

/**
 * Immutable description of one curated REST API Advisor rule. The definition is independent of the
 * application under analysis; the matching {@link RestApiRule} decides whether the rule
 * applies and produces an outcome.
 */
record RestApiRuleDefinition(
        String id,
        String name,
        RestApiCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
