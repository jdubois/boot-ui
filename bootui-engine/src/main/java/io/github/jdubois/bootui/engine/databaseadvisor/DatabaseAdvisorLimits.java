package io.github.jdubois.bootui.engine.databaseadvisor;

import java.time.Duration;

/**
 * The fixed bounds every Database Advisor scan runs under, so an on-demand scan against a very large or very
 * slow schema can never turn into unbounded work on the request thread.
 *
 * <p>Row bounds are enforced by reading one row past the limit ({@code max + 1}), which makes truncation
 * deterministic and observable rather than silently returning a full-looking result. The wall-clock budget
 * stops the scan between units of work, and {@link #statementTimeout()} is applied to every vendor catalog
 * statement through {@code Statement.setQueryTimeout} so a blocked catalog query cannot outlive it.</p>
 */
record DatabaseAdvisorLimits(
        int maxTables,
        int maxColumnsPerTable,
        int maxIndexesPerTable,
        int maxVendorFindings,
        int maxVendorEnrichmentRows,
        Duration scanBudget,
        Duration statementTimeout) {

    static final DatabaseAdvisorLimits DEFAULTS =
            new DatabaseAdvisorLimits(300, 300, 100, 500, 5_000, Duration.ofSeconds(20), Duration.ofSeconds(5));

    DatabaseAdvisorLimits(
            int maxTables,
            int maxColumnsPerTable,
            int maxIndexesPerTable,
            int maxVendorFindings,
            Duration scanBudget,
            Duration statementTimeout) {
        this(
                maxTables,
                maxColumnsPerTable,
                maxIndexesPerTable,
                maxVendorFindings,
                maxVendorFindings,
                scanBudget,
                statementTimeout);
    }

    int rowLimit(VendorFindingKind<?> kind) {
        return kind != null && kind.enrichment() ? maxVendorEnrichmentRows : maxVendorFindings;
    }

    /** The whole-number seconds to hand to {@code Statement.setQueryTimeout}, at least one second. */
    int statementTimeoutSeconds() {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, statementTimeout().toSeconds()));
    }
}
