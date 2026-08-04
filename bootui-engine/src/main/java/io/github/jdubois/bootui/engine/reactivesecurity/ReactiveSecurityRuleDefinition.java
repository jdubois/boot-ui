package io.github.jdubois.bootui.engine.reactivesecurity;

/**
 * Static metadata for one {@code SEC-RXF-*} reactive Spring Security advisor rule.
 */
record ReactiveSecurityRuleDefinition(
        String id,
        String name,
        ReactiveSecurityCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
