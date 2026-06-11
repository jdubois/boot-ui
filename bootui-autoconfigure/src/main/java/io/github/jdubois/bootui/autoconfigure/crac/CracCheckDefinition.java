package io.github.jdubois.bootui.autoconfigure.crac;

/**
 * Immutable description of one curated CRaC checkpoint/restore readiness check. The definition is
 * independent of the application under analysis; the matching {@link CracCheck} decides whether the
 * check triggers and produces an outcome.
 */
record CracCheckDefinition(
        String id,
        String name,
        CracCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
