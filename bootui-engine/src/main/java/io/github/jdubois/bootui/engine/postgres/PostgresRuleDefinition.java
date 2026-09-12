package io.github.jdubois.bootui.engine.postgres;

/**
 * The fixed, published identity of one PostgreSQL check.
 *
 * <p>Ids are stable because they are the anchors in {@code docs/POSTGRESQL-CHECKS.md}; {@link #caveat()} is
 * mandatory rather than optional because every one of these findings is inferred from counters that reset,
 * are per node, or are sampled — saying what a finding cannot prove is part of the finding.</p>
 */
record PostgresRuleDefinition(
        String id,
        String sectionId,
        String title,
        String category,
        String severity,
        String description,
        String recommendation,
        String caveat,
        String learnMoreUrl) {}
