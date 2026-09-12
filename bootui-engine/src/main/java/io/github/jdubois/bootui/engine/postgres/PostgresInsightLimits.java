package io.github.jdubois.bootui.engine.postgres;

import java.time.Duration;

/**
 * The fixed bounds every PostgreSQL read runs under, so an on-demand read against a very large or very busy
 * server can never turn into unbounded work on the request thread.
 *
 * <p>Row bounds are enforced by reading one row past the limit ({@code max + 1}), which makes truncation
 * deterministic and observable rather than silently returning a full-looking result. The wall-clock budget
 * stops the read between collectors, and {@link #statementTimeout()} plus {@link #lockTimeout()} are pinned
 * on the session itself so a blocked statistics query cannot outlive them.</p>
 */
record PostgresInsightLimits(
        int maxStatements,
        int maxIndexes,
        int maxTables,
        int maxVacuumTables,
        int maxReplicas,
        int maxSettings,
        int maxQueryTextLength,
        Duration readBudget,
        Duration statementTimeout,
        Duration lockTimeout) {

    static final PostgresInsightLimits DEFAULTS = new PostgresInsightLimits(
            25, 50, 25, 25, 10, 40, 400, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(2));

    /** The whole-number seconds to hand to {@code Statement.setQueryTimeout}, at least one second. */
    int statementTimeoutSeconds() {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, statementTimeout().toSeconds()));
    }
}
