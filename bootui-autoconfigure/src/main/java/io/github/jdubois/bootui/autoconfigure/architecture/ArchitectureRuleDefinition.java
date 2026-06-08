package io.github.jdubois.bootui.autoconfigure.architecture;

/**
 * Immutable description of one curated architecture rule. The definition is independent of the
 * application under analysis; the matching {@link ArchitectureRule} decides whether the rule
 * applies and produces an outcome.
 */
record ArchitectureRuleDefinition(
        String id,
        String name,
        ArchitectureCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
