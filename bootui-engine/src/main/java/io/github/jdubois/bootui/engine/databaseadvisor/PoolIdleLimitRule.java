package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.IdleLimit;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Tri;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;

/**
 * A HikariCP pool that can keep a connection idle for longer than the database server tolerates. Judged from the
 * pool's own numbers and the server's idle-session limit as the scan connection sees it; it never borrows a connection
 * for the pool half. HikariCP only: Agroal exposes neither a keepalive nor a validation setting, so the judgement
 * would be missing half of its inputs.
 */
final class PoolIdleLimitRule extends AbstractDriverRule {

    static final String ID = "DB-POOL-001";

    PoolIdleLimitRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "Connection pool can keep a connection idle longer than the server allows",
                        DatabaseAdvisorCategory.CONNECTION_POOL,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a HikariCP pool on a PostgreSQL, MySQL or MariaDB datasource that can hold an idle"
                                + " connection longer than the server lets a session stay idle (MySQL/MariaDB"
                                + " wait_timeout, PostgreSQL idle_session_timeout when above 0). It fires only when the"
                                + " pool does nothing about it: maxLifetime is 0 or not below the server limit, no"
                                + " keepaliveTime shorter than the limit pings idle connections, and idle retirement"
                                + " does not get there first (it needs minimumIdle 0 and an idleTimeout, plus the 30"
                                + " seconds of scheduling variation HikariCP documents, below the limit). The pool may"
                                + " then hand out a connection the server has already closed. Whether the application"
                                + " sees an error or only reconnection latency depends on connection validation, which"
                                + " is not read here. The server value is the one the scan connection's own session"
                                + " sees; a pool init statement or driver option can give pooled sessions another.",
                        "Set maxLifetime several seconds shorter than the server's idle-session limit, or set"
                                + " keepaliveTime (at least 30 seconds, below that limit and below maxLifetime) so idle"
                                + " connections are pinged before the server closes them. HikariCP documents that"
                                + " maxLifetime \"should be several seconds shorter than any database or infrastructure"
                                + " imposed connection time limit\". Or raise the server limit (MySQL wait_timeout) or"
                                + " disable it for pooled roles (PostgreSQL idle_session_timeout), which the PostgreSQL"
                                + " manual advises against enforcing on pooled connections.",
                        "https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby"),
                "No PostgreSQL, MySQL or MariaDB datasource was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        Outcome unresolved = unresolved(context, schema);
        if (unresolved != null) {
            return unresolved;
        }
        PoolSettings pool = context.poolOf(schema.dataSourceName());
        IdleLimit limit = PoolServerCompatibility.idleLimit(schema).value();
        Tri exposed = PoolServerCompatibility.exposedToIdleClose(pool, limit.millis());
        if (exposed == Tri.UNKNOWN) {
            return Outcome.gap("a pool setting that decides whether idle connections outlive `" + limit.setting()
                    + "` could not be read, so it is not evaluated.");
        }
        if (exposed == Tri.NO) {
            return Outcome.assessed();
        }
        findings.add(text(schema, pool, limit), identity(schema.dataSourceName()));
        return Outcome.assessed();
    }

    /**
     * Why this datasource cannot be judged, or {@code null} when it can; {@link Outcome#notApplicable()} for a dialect
     * the rule does not cover and {@link Outcome#assessed()} when the server has no idle limit to compare with.
     */
    static Outcome unresolved(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        if (!PoolServerCompatibility.applies(schema)) {
            return Outcome.notApplicable();
        }
        Result<PoolSettings> pool = PoolServerCompatibility.pool(context, schema);
        if (pool.unresolved() != null) {
            return pool.unresolved();
        }
        if (PoolServerCompatibility.agroal(pool.value())) {
            return Outcome.skip("the Agroal pool exposes neither a keepalive nor a validation setting, so whether it"
                    + " keeps idle connections alive is not observable.");
        }
        return PoolServerCompatibility.idleLimit(schema).unresolved();
    }

    static String identity(String dataSourceName) {
        return DatabaseIdentities.of(dataSourceName, "pool=idle-limit");
    }

    /** Kept under the 240 characters a finding text may carry, with the facts first and the label last. */
    private static String text(SchemaSnapshot schema, PoolSettings pool, IdleLimit limit) {
        String lifetime =
                pool.maxLifetimeMs() == 0 ? "0 (none)" : PoolServerCompatibility.duration(pool.maxLifetimeMs());
        String keepalive =
                pool.keepaliveTimeMs() == 0 ? "0 (off)" : PoolServerCompatibility.duration(pool.keepaliveTimeMs());
        String idle = pool.minimumIdle() > 0
                ? "minimumIdle " + pool.minimumIdle() + " (no idle retirement)"
                : pool.idleTimeoutMs() == 0
                        ? "idleTimeout 0 (never)"
                        : "idleTimeout " + PoolServerCompatibility.duration(pool.idleTimeoutMs()) + " (too late)";
        return "Datasource `" + schema.dataSourceName() + "`: maxLifetime " + lifetime + ", keepaliveTime " + keepalive
                + ", " + idle + ", server idle limit " + PoolServerCompatibility.duration(limit.millis()) + " ("
                + limit.setting() + "): the pool may hand out a connection the server already closed. Evidence:"
                + " effective.";
    }
}
