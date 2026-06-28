package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral, <em>unmasked</em> snapshot of one JDBC connection pool known to a
 * {@link ConnectionPoolProvider}. It carries the pool's identity, its (raw) connection metadata, sizing and
 * timeout settings, and — when the pool is reporting live counters — a {@link ConnectionPoolSnapshot}.
 *
 * <p>The {@code jdbcUrl} and {@code username} are the <strong>raw</strong> values: the engine
 * {@code ConnectionPoolService} masks them through {@link ExposurePolicy} before they reach the browser, so
 * the same masking serves both adapters and BootUI never leaks credentials.</p>
 *
 * <p>Fields with no faithful equivalent on a given pool library are carried as {@code -1} (numeric) or
 * {@code null} (string); for example the Quarkus/Agroal adapter has no analogue of HikariCP's keepalive time
 * or per-call validation timeout, so it reports {@code -1} for those (the UI renders them as "—").</p>
 *
 * @param beanName the pool's bean/datasource name (the Spring bean name, or the Quarkus datasource name with
 *     the default datasource rendered as {@code "default"})
 * @param poolName the pool's own name when it exposes one, otherwise the datasource name
 * @param jdbcUrl the <em>raw</em>, unmasked JDBC URL, or {@code null}
 * @param username the <em>raw</em>, unmasked pool username, or {@code null}
 * @param driverClassName the JDBC driver/connection-provider class name, or {@code null} when unknown
 * @param minimumIdle the minimum idle pool size, or {@code -1}
 * @param maximumPoolSize the maximum pool size, or {@code -1}
 * @param connectionTimeoutMs the max wait to acquire a connection, in millis, or {@code -1}
 * @param idleTimeoutMs the idle-eviction threshold, in millis, or {@code -1}
 * @param maxLifetimeMs the maximum connection lifetime, in millis, or {@code -1}
 * @param validationTimeoutMs the validation timeout, in millis, or {@code -1} when the pool library has no
 *     faithful equivalent
 * @param keepaliveTimeMs the keepalive interval, in millis, or {@code -1} when the pool library has no
 *     faithful equivalent
 * @param readOnly whether the datasource is configured read-only
 * @param autoCommit whether connections default to auto-commit
 * @param available whether the pool is reporting live counters (a non-null {@code snapshot})
 * @param unavailableReason a short reason when {@code available} is {@code false}, otherwise {@code null}
 * @param snapshot the live connection counts when reachable, otherwise {@code null}
 */
public record ConnectionPoolInfo(
        String beanName,
        String poolName,
        String jdbcUrl,
        String username,
        String driverClassName,
        int minimumIdle,
        int maximumPoolSize,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long validationTimeoutMs,
        long keepaliveTimeMs,
        boolean readOnly,
        boolean autoCommit,
        boolean available,
        String unavailableReason,
        ConnectionPoolSnapshot snapshot) {}
