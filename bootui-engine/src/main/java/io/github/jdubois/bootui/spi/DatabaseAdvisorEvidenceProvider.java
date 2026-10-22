package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Map;

/**
 * Framework-neutral seam that hands the Database Advisor the evidence it cannot read from a JDBC connection: what the
 * connection pool passes to the driver, and whether the application's Hibernate configuration batches writes. The
 * adapters implement it next to {@link DatabaseAdvisorDataSourceProvider}; the engine owns every judgement.
 *
 * <p>Everything here is sanitized by construction. No record carries a JDBC URL, a user name, a password or a bean
 * name, and the driver properties are restricted to the engine's allow-list of non-secret driver settings. Both reads
 * are meant for an explicit, user-triggered scan: neither borrows a connection or executes SQL.</p>
 */
public interface DatabaseAdvisorEvidenceProvider {

    /**
     * One entry per datasource the advisor may scan, named like {@link NamedDataSource#name()}. A datasource whose
     * pool cannot be attributed with certainty has no entry rather than a guessed one.
     */
    List<DataSourceConfiguration> dataSourceConfigurations();

    /**
     * The effective JDBC batching per datasource name, derived from the Hibernate observations. A datasource that
     * Hibernate does not demonstrably use is absent, which a consumer must read as unknown.
     */
    Map<String, JdbcBatching> jdbcBatching();

    /**
     * @param dataSourceName the datasource name, equal to {@link NamedDataSource#name()}
     * @param pool the sanitized pool settings, or {@code null} when no pool could be attributed
     * @param driverProperties the driver properties the pool supplies, restricted to allow-listed names with values
     *     capped at 64 characters; never a credential
     * @param driverPropertiesKnown whether {@code driverProperties} was actually read for this datasource's pool. It is
     *     {@code false} when the pool could not be attributed or its properties could not be read, in which case an
     *     empty map is the absence of evidence, not evidence of absence
     */
    record DataSourceConfiguration(
            String dataSourceName,
            PoolSettings pool,
            Map<String, String> driverProperties,
            boolean driverPropertiesKnown) {

        public DataSourceConfiguration {
            driverProperties = driverProperties == null ? Map.of() : Map.copyOf(driverProperties);
        }

        /** A configuration whose driver properties were read exactly when a pool was attributed. */
        public DataSourceConfiguration(String dataSourceName, PoolSettings pool, Map<String, String> driverProperties) {
            this(dataSourceName, pool, driverProperties, pool != null);
        }
    }

    /**
     * Sanitized pool numbers only: no URL, user name, bean name or password. A setting the pool library does not
     * expose is {@code null}; {@code -1} marks a pool size that could not be read.
     *
     * @param implementation {@link ConnectionPoolInfo#HIKARI} or {@link ConnectionPoolInfo#AGROAL}
     */
    record PoolSettings(
            String implementation,
            int maximumPoolSize,
            int minimumIdle,
            Long idleTimeoutMs,
            Long maxLifetimeMs,
            Long keepaliveTimeMs,
            Long connectionTimeoutMs) {}

    /** {@code batchSize} is meaningful only when {@code status} is {@link Status#ENABLED}. */
    record JdbcBatching(Status status, int batchSize) {

        public enum Status {
            ENABLED,
            DISABLED,
            UNKNOWN
        }

        public static JdbcBatching enabled(int batchSize) {
            return new JdbcBatching(Status.ENABLED, batchSize);
        }

        public static JdbcBatching disabled() {
            return new JdbcBatching(Status.DISABLED, 0);
        }

        public static JdbcBatching unknown() {
            return new JdbcBatching(Status.UNKNOWN, 0);
        }
    }
}
