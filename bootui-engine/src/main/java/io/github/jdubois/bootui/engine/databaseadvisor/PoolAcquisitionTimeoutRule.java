package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;

/**
 * A HikariCP or Agroal connection pool whose connection acquisition timeout
 * ({@link PoolSettings#connectionTimeoutMs()}) is disabled ({@code 0} on Agroal, or {@code Integer.MAX_VALUE}
 * from {@code 0} on HikariCP) or excessive (above {@value #MAX_ACQUISITION_TIMEOUT_MS} ms, five minutes).
 */
final class PoolAcquisitionTimeoutRule extends AbstractDriverRule {

    static final String ID = "DB-POOL-005";
    static final long MAX_ACQUISITION_TIMEOUT_MS = 300_000L;

    PoolAcquisitionTimeoutRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "Connection pool has a disabled or excessive acquisition timeout",
                        DatabaseAdvisorCategory.CONNECTION_POOL,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a HikariCP or Agroal connection pool whose connection acquisition timeout"
                                + " (connectionTimeout on HikariCP, acquisition-timeout on Agroal) is disabled (0 on"
                                + " Agroal, or Integer.MAX_VALUE ms when HikariCP converts 0) or exceeds 5 minutes"
                                + " (300,000 ms). Callers waiting on an exhausted pool or an unreachable database then"
                                + " block for minutes or indefinitely instead of failing fast.",
                        "Configure a bounded connection acquisition timeout (HikariCP defaults to 30 seconds via"
                                + " spring.datasource.hikari.connection-timeout; Quarkus Agroal defaults to 5 seconds"
                                + " via quarkus.datasource.jdbc.acquisition-timeout) so callers fail fast under pool"
                                + " exhaustion.",
                        "https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby"),
                "No PostgreSQL, MySQL or MariaDB datasource, or attributed HikariCP/Agroal pool, was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        if (!PoolServerCompatibility.applies(schema) && context.poolOf(schema.dataSourceName()) == null) {
            return Outcome.notApplicable();
        }
        Result<PoolSettings> pool = PoolServerCompatibility.pool(context, schema);
        if (pool.unresolved() != null) {
            return pool.unresolved();
        }
        Long timeoutMs = pool.value().connectionTimeoutMs();
        if (timeoutMs == null || timeoutMs < 0) {
            return Outcome.gap("the pool's `connectionTimeout` could not be read, so it is not evaluated.");
        }
        boolean agroal = PoolServerCompatibility.agroal(pool.value());
        String settingName = agroal ? "acquisitionTimeout" : "connectionTimeout";
        if (timeoutMs == 0) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: " + settingName
                            + " is 0 (disabled), so callers wait indefinitely when the pool is exhausted."
                            + " Evidence: effective.",
                    identity(schema.dataSourceName()));
        } else if (!agroal && timeoutMs == Integer.MAX_VALUE) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: connectionTimeout is " + timeoutMs
                            + " ms (Integer.MAX_VALUE, HikariCP's representation of 0 / disabled), so callers wait"
                            + " indefinitely when the pool is exhausted. Evidence: effective.",
                    identity(schema.dataSourceName()));
        } else if (timeoutMs > MAX_ACQUISITION_TIMEOUT_MS) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: " + settingName + " is "
                            + PoolServerCompatibility.duration(timeoutMs) + " (above "
                            + PoolServerCompatibility.duration(MAX_ACQUISITION_TIMEOUT_MS)
                            + "), so callers can block for minutes when the pool is exhausted. Evidence: effective.",
                    identity(schema.dataSourceName()));
        }
        return Outcome.assessed();
    }

    static String identity(String dataSourceName) {
        return DatabaseIdentities.of(dataSourceName, "pool=connection-timeout");
    }
}
