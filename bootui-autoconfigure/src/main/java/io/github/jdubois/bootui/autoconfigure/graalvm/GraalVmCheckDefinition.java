package io.github.jdubois.bootui.autoconfigure.graalvm;

/**
 * Immutable description of one curated native-image readiness check. The definition is independent
 * of the application under analysis; the matching {@link GraalVmCheck} decides whether the check
 * triggers and produces an outcome.
 */
record GraalVmCheckDefinition(
        String id,
        String name,
        GraalVmCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
